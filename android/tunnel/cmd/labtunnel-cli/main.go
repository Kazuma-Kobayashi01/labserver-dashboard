// labtunnel-cli runs the same tunnel + proxy the Android app uses, on a desktop, for testing:
//
//	labtunnel-cli config.json   # then: curl -x http://127.0.0.1:<port> http://bot.labserver.internal/...
package main

import (
	"fmt"
	"os"
	"os/signal"
	"time"

	"lablink.local/tunnel/labtunnel"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: labtunnel-cli config.json")
		os.Exit(2)
	}
	cfg, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	port, err := labtunnel.Start(string(cfg))
	if err != nil {
		fmt.Fprintln(os.Stderr, "start:", err)
		os.Exit(1)
	}
	defer labtunnel.Stop()
	fmt.Printf("proxy http://127.0.0.1:%d\n", port)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt)
	tick := time.NewTicker(5 * time.Second)
	for {
		select {
		case <-tick.C:
			fmt.Println(labtunnel.Stats())
			if l := labtunnel.Log(); l != "" {
				fmt.Println(l)
			}
		case <-stop:
			return
		}
	}
}
