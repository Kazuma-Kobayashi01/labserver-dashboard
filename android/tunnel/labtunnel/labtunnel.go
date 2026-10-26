// Package labtunnel runs a WireGuard tunnel entirely inside the app process (wireguard-go on a userspace netstack),
// so no Android VpnService is involved: nothing outside the app can use the tunnel, and nothing is routed through it
// unless the app sends it there. The app reaches the home network through a small HTTP proxy bound to 127.0.0.1;
// the proxy only dials a short allow-list of home hostnames, so even another app on the phone that finds the port
// cannot use it as a general gateway.
//
// Built with gomobile (see ../build-aar.sh); the exported API sticks to types gomobile can pass.
package labtunnel

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type config struct {
	PrivateKey    string `json:"privateKey"`    // base64, this device
	Address       string `json:"address"`       // this device's overlay address, e.g. 10.77.0.4
	PeerPublicKey string `json:"peerPublicKey"` // base64, the hub
	PresharedKey  string `json:"presharedKey"`  // base64, optional
	Endpoint      string `json:"endpoint"`      // hub host:port
	AllowedIPs    string `json:"allowedIPs"`    // comma separated, e.g. 10.77.0.0/24,10.0.0.0/24
	Keepalive     int    `json:"keepalive"`     // seconds, 0 = off
	MTU           int    `json:"mtu"`           // default 1280
	AllowedHosts  string `json:"allowedHosts"`  // comma separated host suffixes the proxy may reach
	TunnelHost    string `json:"tunnelHost"`    // address every allowed host resolves to inside the tunnel
}

type instance struct {
	cfg  config
	dev  *device.Device
	tnet *netstack.Net
	ln   net.Listener
	srv  *http.Server
}

var (
	mu   sync.Mutex
	cur  *instance
	logs = &ring{max: 60}
)

// GeneratePrivateKey returns a new WireGuard private key (base64).
func GeneratePrivateKey() (string, error) {
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(k.Bytes()), nil
}

// PublicKeyOf derives the public key (base64) from a private key (base64).
func PublicKeyOf(privateKey string) (string, error) {
	raw, err := decodeKey(privateKey)
	if err != nil {
		return "", err
	}
	k, err := ecdh.X25519().NewPrivateKey(raw)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(k.PublicKey().Bytes()), nil
}

// Start brings the tunnel up and returns the loopback port of the proxy. configJSON is the config struct above.
// Calling Start while running restarts the tunnel.
func Start(configJSON string) (int, error) {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()

	var c config
	if err := json.Unmarshal([]byte(configJSON), &c); err != nil {
		return 0, fmt.Errorf("bad config: %w", err)
	}
	if c.MTU == 0 {
		c.MTU = 1280
	}
	if c.TunnelHost == "" {
		return 0, errors.New("bad config: tunnelHost is empty")
	}
	priv, err := decodeKey(c.PrivateKey)
	if err != nil {
		return 0, fmt.Errorf("private key: %w", err)
	}
	pub, err := decodeKey(c.PeerPublicKey)
	if err != nil {
		return 0, fmt.Errorf("hub public key: %w", err)
	}
	addr, err := netip.ParseAddr(strings.TrimSpace(c.Address))
	if err != nil {
		return 0, fmt.Errorf("address: %w", err)
	}
	ep, err := net.ResolveUDPAddr("udp", c.Endpoint)
	if err != nil {
		return 0, fmt.Errorf("endpoint: %w", err)
	}

	tun, tnet, err := netstack.CreateNetTUN([]netip.Addr{addr}, nil, c.MTU)
	if err != nil {
		return 0, fmt.Errorf("netstack: %w", err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf:   func(f string, a ...any) { logs.add(fmt.Sprintf(f, a...)) },
	})

	var ipc strings.Builder
	fmt.Fprintf(&ipc, "private_key=%s\n", hex.EncodeToString(priv))
	fmt.Fprintf(&ipc, "public_key=%s\n", hex.EncodeToString(pub))
	if c.PresharedKey != "" {
		psk, err := decodeKey(c.PresharedKey)
		if err != nil {
			dev.Close()
			return 0, fmt.Errorf("preshared key: %w", err)
		}
		fmt.Fprintf(&ipc, "preshared_key=%s\n", hex.EncodeToString(psk))
	}
	fmt.Fprintf(&ipc, "endpoint=%s\n", ep.String())
	fmt.Fprintf(&ipc, "persistent_keepalive_interval=%d\n", c.Keepalive)
	for _, a := range strings.Split(c.AllowedIPs, ",") {
		if a = strings.TrimSpace(a); a != "" {
			fmt.Fprintf(&ipc, "allowed_ip=%s\n", a)
		}
	}
	if err := dev.IpcSet(ipc.String()); err != nil {
		dev.Close()
		return 0, fmt.Errorf("configure: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return 0, fmt.Errorf("up: %w", err)
	}

	in := &instance{cfg: c, dev: dev, tnet: tnet}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		dev.Close()
		return 0, fmt.Errorf("proxy listen: %w", err)
	}
	in.ln = ln
	in.srv = &http.Server{Handler: in.handler(), ReadHeaderTimeout: 20 * time.Second}
	go in.srv.Serve(ln) //nolint:errcheck // returns on Close
	cur = in
	return ln.Addr().(*net.TCPAddr).Port, nil
}

// Stop tears the tunnel and the proxy down. Safe to call when not running.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	if cur == nil {
		return
	}
	cur.srv.Close()
	cur.dev.Close()
	cur = nil
}

// Rebind reopens the UDP socket; call it after the phone changes network (Wi-Fi <-> mobile data) so the
// tunnel does not keep sending from an address that no longer exists.
func Rebind() {
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		if err := cur.dev.BindUpdate(); err != nil {
			logs.add("rebind: " + err.Error())
		}
	}
}

// Stats returns {"running":bool,"handshakeAgo":seconds or -1,"rx":bytes,"tx":bytes} as JSON.
func Stats() string {
	mu.Lock()
	defer mu.Unlock()
	out := map[string]any{"running": false, "handshakeAgo": -1, "rx": 0, "tx": 0}
	if cur != nil {
		out["running"] = true
		dump, err := cur.dev.IpcGet()
		if err == nil {
			for _, line := range strings.Split(dump, "\n") {
				k, v, ok := strings.Cut(line, "=")
				if !ok {
					continue
				}
				n, _ := strconv.ParseInt(v, 10, 64)
				switch k {
				case "last_handshake_time_sec":
					if n > 0 {
						out["handshakeAgo"] = time.Now().Unix() - n
					}
				case "rx_bytes":
					out["rx"] = n
				case "tx_bytes":
					out["tx"] = n
				}
			}
		}
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// Log returns the most recent tunnel error lines, newest last.
func Log() string { return logs.String() }

// ---- proxy ----

func (in *instance) handler() http.Handler {
	rp := &httputil.ReverseProxy{
		Director: func(*http.Request) {}, // a proxy request already carries an absolute URL
		Transport: &http.Transport{
			DialContext:         in.dial,
			MaxIdleConns:        32,
			IdleConnTimeout:     60 * time.Second,
			TLSHandshakeTimeout: 15 * time.Second,
		},
		FlushInterval: -1, // stream server-sent events straight through
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			http.Error(w, "lab tunnel: "+err.Error(), http.StatusBadGateway)
		},
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodConnect:
			in.connect(w, r)
		case r.URL.Host == "":
			http.Error(w, "this is a proxy port", http.StatusBadRequest)
		default:
			rp.ServeHTTP(w, r)
		}
	})
}

func (in *instance) connect(w http.ResponseWriter, r *http.Request) {
	up, err := in.dial(r.Context(), "tcp", r.Host)
	if err != nil {
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		up.Close()
		http.Error(w, "cannot hijack", http.StatusInternalServerError)
		return
	}
	c, _, err := hj.Hijack()
	if err != nil {
		up.Close()
		return
	}
	c.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")) //nolint:errcheck
	go func() { io.Copy(up, c); up.Close() }()                      //nolint:errcheck
	io.Copy(c, up)                                                  //nolint:errcheck
	c.Close()
}

// dial resolves allowed names to the tunnel host and refuses everything else.
func (in *instance) dial(ctx context.Context, _, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		host, port = addr, "80"
	}
	if port != "80" && port != "443" {
		return nil, fmt.Errorf("port %s is not allowed", port)
	}
	if !in.hostAllowed(host) {
		return nil, fmt.Errorf("host %q is not allowed", host)
	}
	return in.tnet.DialContext(ctx, "tcp", net.JoinHostPort(in.cfg.TunnelHost, port))
}

func (in *instance) hostAllowed(host string) bool {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	if host == in.cfg.TunnelHost {
		return true
	}
	for _, s := range strings.Split(in.cfg.AllowedHosts, ",") {
		s = strings.Trim(strings.ToLower(strings.TrimSpace(s)), ".")
		if s != "" && (host == s || strings.HasSuffix(host, "."+s)) {
			return true
		}
	}
	return false
}

// ---- helpers ----

func decodeKey(b64 string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(raw) != 32 {
		return nil, errors.New("not a valid WireGuard key")
	}
	return raw, nil
}

type ring struct {
	mu    sync.Mutex
	lines []string
	max   int
}

func (r *ring) add(s string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, time.Now().Format("15:04:05 ")+strings.TrimSpace(s))
	if len(r.lines) > r.max {
		r.lines = r.lines[len(r.lines)-r.max:]
	}
}

func (r *ring) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}
