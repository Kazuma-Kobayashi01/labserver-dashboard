# Legacy: reverse-SSH tunnel (superseded by WireGuard + LabLink)

The first remote-access design. It works, but SSH-inside-SSH over TCP is slow on lossy links, so the main
path is now the WireGuard overlay (`../wireguard`) driven by the LabLink macOS app. Kept as a fallback.

- `labserver-tunnel.service` – systemd unit on the home server: keeps `-R 127.0.0.1:2222` open on a public relay.
  Replace `YOUR_USER` and `VPS_IP`, and create a dedicated key (`~/.ssh/id_ed25519_tunnel`) restricted on the relay with
  `restrict,permitlisten="127.0.0.1:2222"`.
- `50-tunnel-keyonly.conf` – append to the home server's `sshd_config`: tunnel arrivals (from 127.0.0.1) are key-only.
- `mbk` – Mac helper script. Expects `~/.ssh/config` hosts `home` (LAN), `labserver-relay` (the public relay) and
  `home-away` (ProxyJump through the relay). `mbk -w` opens the web panels through a SOCKS tunnel in a dedicated Chrome profile.
