# WireGuard overlay (10.77.0.0/24)
- VPS hub (Docker `wg-hub`, UDP 51820, GCP firewall rule required): `hub/`
- Home gateway (Incus `wg-gw` @ 10.0.0.30): `gw/`
- Mac: peer 10.77.0.3, AllowedIPs 10.77.0.0/24, 10.0.0.0/24 (config in `~/.config/lablink/lablink.conf`, driven by LabLink)
- the home server's mihomo needs `- IP-CIDR,<VPS_PUBLIC_IP>/32,DIRECT,no-resolve` as the first rule, otherwise the tunnel
  (and the old SSH reverse tunnel) is routed through a proxy node and gets 2-3x latency. Same applies on the Mac's Clash.
