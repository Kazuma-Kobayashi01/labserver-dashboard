// Package incus is a minimal read-only client for the Incus REST API.
// It only ever issues GET requests.
package incus

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

type Client struct {
	base string
	http *http.Client
}

// New builds a client that authenticates with a TLS client certificate.
// The server certificate is not verified because the API is only reached over loopback.
func New(baseURL, certFile, keyFile string) (*Client, error) {
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, err
	}
	return &Client{
		base: strings.TrimRight(baseURL, "/"),
		http: &http.Client{
			Timeout: 8 * time.Second,
			Transport: &http.Transport{TLSClientConfig: &tls.Config{
				Certificates:       []tls.Certificate{cert},
				InsecureSkipVerify: true, //nolint:gosec // loopback only
			}},
		},
	}, nil
}

type Instance struct {
	Name        string   `json:"name"`
	Type        string   `json:"type"` // container | virtual-machine
	Status      string   `json:"status"`
	Running     bool     `json:"running"`
	OS          string   `json:"os"`
	Release     string   `json:"release"`
	IPv4        []string `json:"ipv4"`
	MemoryUsed  uint64   `json:"memoryUsed"`
	MemoryLimit uint64   `json:"memoryLimit"` // 0 = unlimited
	CPUSeconds  float64  `json:"cpuSeconds"`
	CPULimit    string   `json:"cpuLimit"`
	Processes   int64    `json:"processes"`
	Since       string   `json:"since,omitempty"` // last start time, RFC3339, only when running
	Created     string   `json:"created"`
}

type apiResponse struct {
	StatusCode int             `json:"status_code"`
	Error      string          `json:"error"`
	Metadata   json.RawMessage `json:"metadata"`
}

func (c *Client) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
	if err != nil {
		return err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var r apiResponse
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("incus: %s (%d)", r.Error, resp.StatusCode)
	}
	return json.Unmarshal(r.Metadata, out)
}

// Healthy reports whether the API answers.
func (c *Client) Healthy(ctx context.Context) error {
	var v map[string]any
	return c.get(ctx, "/1.0", &v)
}

func (c *Client) Instances(ctx context.Context) ([]Instance, error) {
	var raw []struct {
		Name           string            `json:"name"`
		Type           string            `json:"type"`
		Status         string            `json:"status"`
		CreatedAt      string            `json:"created_at"`
		LastUsedAt     string            `json:"last_used_at"`
		ExpandedConfig map[string]string `json:"expanded_config"`
		State          *struct {
			Memory struct {
				Usage int64 `json:"usage"`
			} `json:"memory"`
			CPU struct {
				Usage int64 `json:"usage"`
			} `json:"cpu"`
			Processes int64 `json:"processes"`
			Network   map[string]struct {
				Type      string `json:"type"`
				Addresses []struct {
					Family  string `json:"family"`
					Address string `json:"address"`
					Scope   string `json:"scope"`
				} `json:"addresses"`
			} `json:"network"`
		} `json:"state"`
	}
	if err := c.get(ctx, "/1.0/instances?recursion=2&project=default", &raw); err != nil {
		return nil, err
	}
	out := make([]Instance, 0, len(raw))
	for _, r := range raw {
		in := Instance{
			Name: r.Name, Type: r.Type, Status: r.Status, Running: r.Status == "Running",
			OS:       r.ExpandedConfig["image.os"],
			Release:  r.ExpandedConfig["image.release"],
			CPULimit: r.ExpandedConfig["limits.cpu"],
			Created:  r.CreatedAt,
			IPv4:     []string{},
		}
		if in.OS == "" {
			in.OS = r.ExpandedConfig["image.description"]
		}
		if lim := r.ExpandedConfig["limits.memory"]; lim != "" {
			in.MemoryLimit = parseSize(lim)
		}
		if r.State != nil && in.Running {
			in.MemoryUsed = uint64(max(r.State.Memory.Usage, 0))
			in.CPUSeconds = float64(r.State.CPU.Usage) / 1e9
			in.Processes = r.State.Processes
			in.Since = r.LastUsedAt
			for name, n := range r.State.Network {
				if name == "lo" || n.Type == "loopback" {
					continue
				}
				for _, a := range n.Addresses {
					if a.Family == "inet" && a.Scope == "global" {
						in.IPv4 = append(in.IPv4, a.Address)
					}
				}
			}
			sort.Strings(in.IPv4)
		}
		out = append(out, in)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

// parseSize parses Incus size strings such as "4GiB", "512MiB" or "50%". Percentages return 0.
func parseSize(s string) uint64 {
	s = strings.TrimSpace(s)
	units := []struct {
		suf string
		mul uint64
	}{{"TiB", 1 << 40}, {"GiB", 1 << 30}, {"MiB", 1 << 20}, {"KiB", 1 << 10}, {"TB", 1e12}, {"GB", 1e9}, {"MB", 1e6}, {"kB", 1e3}, {"B", 1}}
	for _, u := range units {
		if v, ok := strings.CutSuffix(s, u.suf); ok {
			n, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
			if err != nil {
				return 0
			}
			return uint64(n * float64(u.mul))
		}
	}
	n, _ := strconv.ParseUint(s, 10, 64)
	return n
}
