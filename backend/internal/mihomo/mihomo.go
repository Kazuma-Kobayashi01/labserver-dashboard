// Package mihomo reads status from the mihomo (Clash.Meta) external controller. Read-only.
package mihomo

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

type Client struct {
	base   string
	secret string
	http   *http.Client
}

func New(baseURL, secret string) *Client {
	return &Client{base: strings.TrimRight(baseURL, "/"), secret: secret, http: &http.Client{Timeout: 4 * time.Second}}
}

type Status struct {
	Version       string `json:"version"`
	Mode          string `json:"mode"`
	TUN           bool   `json:"tun"`
	MixedPort     int    `json:"mixedPort"`
	Connections   int    `json:"connections"`
	UploadTotal   int64  `json:"uploadTotal"`
	DownloadTotal int64  `json:"downloadTotal"`
	Memory        int64  `json:"memory"`
}

func (c *Client) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
	if err != nil {
		return err
	}
	if c.secret != "" {
		req.Header.Set("Authorization", "Bearer "+c.secret)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("mihomo: %s", resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func (c *Client) Status(ctx context.Context) (Status, error) {
	var s Status
	var ver struct {
		Version string `json:"version"`
	}
	if err := c.get(ctx, "/version", &ver); err != nil {
		return s, err
	}
	s.Version = ver.Version

	var cfg struct {
		Mode      string `json:"mode"`
		MixedPort int    `json:"mixed-port"`
		TUN       struct {
			Enable bool `json:"enable"`
		} `json:"tun"`
	}
	if err := c.get(ctx, "/configs", &cfg); err == nil {
		s.Mode, s.MixedPort, s.TUN = cfg.Mode, cfg.MixedPort, cfg.TUN.Enable
	}

	var conns struct {
		DownloadTotal int64             `json:"downloadTotal"`
		UploadTotal   int64             `json:"uploadTotal"`
		Memory        int64             `json:"memory"`
		Connections   []json.RawMessage `json:"connections"`
	}
	if err := c.get(ctx, "/connections", &conns); err == nil {
		s.DownloadTotal, s.UploadTotal, s.Memory, s.Connections = conns.DownloadTotal, conns.UploadTotal, conns.Memory, len(conns.Connections)
	}
	return s, nil
}
