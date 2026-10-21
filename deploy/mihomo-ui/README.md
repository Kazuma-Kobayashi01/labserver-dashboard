# MetaCubeXD (Mihomo web UI)

The UI is a third-party static bundle and is not stored in this repo. Download the release build and unpack it
into the directory that `deploy/nginx/mihomo` serves:

```bash
sudo mkdir -p /opt/labserver-dashboard/mihomo-ui
curl -L https://github.com/MetaCubeX/metacubexd/releases/latest/download/compressed-dist.tgz \
  | sudo tar -xz -C /opt/labserver-dashboard/mihomo-ui
```

MetaCubeXD is MIT licensed: https://github.com/MetaCubeX/metacubexd
