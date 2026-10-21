package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"time"

	"labserver/dashboard/internal/api"
	"labserver/dashboard/internal/deepseek"
	"labserver/dashboard/internal/incus"
	"labserver/dashboard/internal/metrics"
	"labserver/dashboard/internal/mihomo"
	"labserver/dashboard/internal/services"
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	listen := env("LISTEN_ADDR", "127.0.0.1:18080")

	col := metrics.New(env("NET_IFACE", ""))
	go col.Run(2 * time.Second)

	srv := &api.Server{Metrics: col}

	if cert := os.Getenv("INCUS_CERT"); cert != "" {
		cl, err := incus.New(env("INCUS_URL", "https://127.0.0.1:8443"), cert, os.Getenv("INCUS_KEY"))
		if err != nil {
			log.Printf("incus disabled: %v", err)
		} else {
			srv.Incus = cl
		}
	} else {
		log.Printf("incus disabled: INCUS_CERT not set")
	}

	if u := os.Getenv("MIHOMO_URL"); u != "" {
		srv.Mihomo = mihomo.New(u, os.Getenv("MIHOMO_SECRET"))
	}

	if kf := os.Getenv("DEEPSEEK_KEY_FILE"); kf != "" {
		ds, err := deepseek.New(kf, os.Getenv("STATE_DIRECTORY"))
		if err != nil {
			log.Printf("deepseek disabled: %v", err)
		} else {
			srv.DeepSeek = ds
			srv.HermesUsageFile = os.Getenv("HERMES_USAGE_FILE")
			go ds.Run(context.Background())
		}
	}

	defs, err := services.Load(env("SERVICES_FILE", "services.json"))
	if err != nil {
		log.Printf("no service registry: %v", err)
	}
	srv.Services = services.NewChecker(defs, srv.InstanceStatus)

	h := &http.Server{
		Addr:              listen,
		Handler:           srv.Router(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      20 * time.Second,
	}
	log.Printf("labserver-dashboard listening on %s", listen)
	log.Fatal(h.ListenAndServe())
}
