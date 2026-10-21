// Package api wires the HTTP routes. Every route is read-only.
package api

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"labserver/dashboard/internal/deepseek"
	"labserver/dashboard/internal/incus"
	"labserver/dashboard/internal/metrics"
	"labserver/dashboard/internal/mihomo"
	"labserver/dashboard/internal/services"
)

type Server struct {
	Metrics  *metrics.Collector
	Incus    *incus.Client // may be nil when no certificate is configured
	Services *services.Checker
	Mihomo   *mihomo.Client // may be nil
	DeepSeek *deepseek.Client // may be nil
	HermesUsageFile string

	mu       sync.Mutex
	instCach []incus.Instance
	instAt   time.Time
}

type envelope struct {
	OK    bool   `json:"ok"`
	Data  any    `json:"data,omitempty"`
	Error string `json:"error,omitempty"`
}

func (s *Server) Router() *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	_ = r.SetTrustedProxies([]string{"127.0.0.1"})

	v1 := r.Group("/api/v1")
	v1.Use(func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		c.Next()
	})
	v1.GET("/health", func(c *gin.Context) { ok(c, gin.H{"status": "ok"}) })
	v1.GET("/system", func(c *gin.Context) { ok(c, s.Metrics.Snapshot()) })
	v1.GET("/details", func(c *gin.Context) { ok(c, s.Metrics.Details()) })
	v1.GET("/metrics", func(c *gin.Context) { ok(c, s.Metrics.History()) })
	v1.GET("/instances", s.instances)
	v1.GET("/mihomo", s.mihomoStatus)
	v1.GET("/deepseek", s.deepseekStatus)
	v1.GET("/services", func(c *gin.Context) { ok(c, s.Services.Check(c.Request.Context())) })
	return r
}

func ok(c *gin.Context, data any) { c.JSON(http.StatusOK, envelope{OK: true, Data: data}) }

func fail(c *gin.Context, code int, msg string) {
	c.JSON(code, envelope{OK: false, Error: msg})
}

func (s *Server) listInstances(ctx context.Context) ([]incus.Instance, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.instCach != nil && time.Since(s.instAt) < 3*time.Second {
		return s.instCach, nil
	}
	list, err := s.Incus.Instances(ctx)
	if err != nil {
		return nil, err
	}
	s.instCach, s.instAt = list, time.Now()
	return list, nil
}

// InstanceStatus is used by the service checker for kind "incus".
func (s *Server) InstanceStatus(ctx context.Context, name string) (string, error) {
	if s.Incus == nil {
		return "", http.ErrNotSupported
	}
	list, err := s.listInstances(ctx)
	if err != nil {
		return "", err
	}
	for _, i := range list {
		if i.Name == name {
			return i.Status, nil
		}
	}
	return "Missing", nil
}

func (s *Server) instances(c *gin.Context) {
	if s.Incus == nil {
		fail(c, http.StatusServiceUnavailable, "incus client is not configured")
		return
	}
	list, err := s.listInstances(c.Request.Context())
	if err != nil {
		fail(c, http.StatusBadGateway, "incus is unreachable")
		return
	}
	ok(c, list)
}

func (s *Server) mihomoStatus(c *gin.Context) {
	if s.Mihomo == nil {
		fail(c, http.StatusServiceUnavailable, "mihomo is not configured")
		return
	}
	st, err := s.Mihomo.Status(c.Request.Context())
	if err != nil {
		fail(c, http.StatusBadGateway, "mihomo is unreachable")
		return
	}
	ok(c, st)
}

type deepseekResponse struct {
	deepseek.Snapshot
	Hermes *deepseek.HermesUsage `json:"hermes"`
}

func (s *Server) deepseekStatus(c *gin.Context) {
	if s.DeepSeek == nil {
		fail(c, http.StatusServiceUnavailable, "deepseek is not configured")
		return
	}
	resp := deepseekResponse{Snapshot: s.DeepSeek.Snapshot()}
	if s.HermesUsageFile != "" {
		resp.Hermes = deepseek.ReadHermes(s.HermesUsageFile)
	}
	ok(c, resp)
}
