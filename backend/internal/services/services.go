// Package services checks the health of the services listed in a JSON registry.
package services

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"time"
)

// Def is one entry of the registry file.
type Def struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Kind        string `json:"kind"`           // systemd | tcp | http | incus
	Target      string `json:"target"`         // unit name | host:port | URL | instance name
	Host        string `json:"host,omitempty"` // optional Host header for http checks
	URL         string `json:"url,omitempty"`  // link opened by the dashboard
	Icon        string `json:"icon,omitempty"`
}

type Status struct {
	Def
	Status    string `json:"status"` // up | down
	Detail    string `json:"detail,omitempty"`
	LatencyMs int64  `json:"latencyMs"`
}

// InstanceState resolves an Incus instance name to its status string.
type InstanceState func(ctx context.Context, name string) (string, error)

func Load(path string) ([]Def, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var defs []Def
	return defs, json.Unmarshal(b, &defs)
}

type Checker struct {
	defs     []Def
	instance InstanceState
	hc       *http.Client

	mu     sync.Mutex
	cached []Status
	at     time.Time
}

func NewChecker(defs []Def, inst InstanceState) *Checker {
	return &Checker{
		defs: defs, instance: inst,
		hc: &http.Client{
			Timeout:       3 * time.Second,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}
}

// Check runs all checks concurrently; results are cached for a few seconds.
func (c *Checker) Check(ctx context.Context) []Status {
	c.mu.Lock()
	defer c.mu.Unlock()
	if time.Since(c.at) < 5*time.Second && c.cached != nil {
		return c.cached
	}
	out := make([]Status, len(c.defs))
	var wg sync.WaitGroup
	for i, d := range c.defs {
		wg.Add(1)
		go func(i int, d Def) {
			defer wg.Done()
			out[i] = c.one(ctx, d)
		}(i, d)
	}
	wg.Wait()
	c.cached, c.at = out, time.Now()
	return out
}

func (c *Checker) one(ctx context.Context, d Def) Status {
	start := time.Now()
	s := Status{Def: d, Status: "down"}
	switch d.Kind {
	case "systemd":
		// Target comes only from the registry file, never from a request.
		out, _ := exec.CommandContext(ctx, "systemctl", "is-active", d.Target).Output()
		state := string(trimNL(out))
		s.Detail = state
		if state == "active" {
			s.Status = "up"
		}
	case "tcp":
		conn, err := (&net.Dialer{Timeout: 2 * time.Second}).DialContext(ctx, "tcp", d.Target)
		if err == nil {
			conn.Close()
			s.Status = "up"
		} else {
			s.Detail = "connection refused"
		}
	case "http":
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, d.Target, nil)
		if err == nil {
			if d.Host != "" {
				req.Host = d.Host
			}
			resp, err := c.hc.Do(req)
			if err == nil {
				resp.Body.Close()
				s.Detail = resp.Status
				if resp.StatusCode < 500 {
					s.Status = "up"
				}
			} else {
				s.Detail = "no response"
			}
		}
	case "incus":
		st, err := c.instance(ctx, d.Target)
		s.Detail = st
		if err != nil {
			s.Detail = "unavailable"
		} else if st == "Running" {
			s.Status = "up"
		}
	}
	s.LatencyMs = time.Since(start).Milliseconds()
	return s
}

func trimNL(b []byte) []byte {
	for len(b) > 0 && (b[len(b)-1] == '\n' || b[len(b)-1] == '\r') {
		b = b[:len(b)-1]
	}
	return b
}
