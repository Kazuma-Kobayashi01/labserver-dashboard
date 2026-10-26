# WireGuard overlay (10.77.0.0/24)
- VPS hub (Docker `wg-hub`, UDP 51820, GCP firewall rule required): `hub/`
- Home gateway (Incus `wg-gw` @ 10.0.0.30): `gw/`
- Mac: peer 10.77.0.3, AllowedIPs 10.77.0.0/24, 10.0.0.0/24 (config in `~/.config/lablink/lablink.conf`, driven by LabLink)
- the home server's mihomo needs `- IP-CIDR,<VPS_PUBLIC_IP>/32,DIRECT,no-resolve` as the first rule, otherwise the tunnel
  (and the old SSH reverse tunnel) is routed through a proxy node and gets 2-3x latency. Same applies on the Mac's Clash.

## Adding a device (phone, tablet, another computer)
`add-device.sh` adds one device to the overlay: a `[Peer]` on the hub, plus the forwarding rules on the hub and on the home
gateway so the device can reach the home network (`10.0.0.0/24`). It edits the config files **and** applies the change
live, after backing both files up (`wg0.conf.bak-<timestamp>`); the rules are idempotent (`-C || -A`).
```bash
HUB_SSH=you@your-vps HOME_SSH=you@home-server \
  ./add-device.sh --dry-run 10.77.0.4 '<device public key>' phone     # review what it would run
HUB_SSH=you@your-vps HOME_SSH=you@home-server \
  ./add-device.sh 10.77.0.4 '<device public key>' phone
```
The device generates its own key pair and only hands over the public key (the Android app shows it on the Link tab).
Use `10.77.0.4` and up; `.1` is the hub, `.2` the home gateway, `.3` the Mac. To remove a device, delete its `[Peer]` and
its three/two `PostUp` lines from both configs and run `wg set wg0 peer <key> remove` in the hub container.
This does not let devices reach each other, only the home network; device-to-device access needs extra hub rules.

The `PostUp` rules in the templates are idempotent as well. (Older live configs that append with a plain `-A` collect a
duplicate set on every `wg-quick` restart; harmless, but you can switch them to the `-C ... || -A ...` form.)
