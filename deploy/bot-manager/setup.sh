#!/bin/bash
# Sets up the bot-manager inside the `hermes` Incus container. Run as root there; safe to run again.
#   incus exec hermes -- bash /opt/bot-manager/setup.sh
# Does: bots.slice (memory cap for all sandboxes), Docker cgroup-parent, the sandbox firewall (no access to the home LAN),
# the `bot-template` Hermes profile every bot is cloned from, and the bot-manager service itself.
set -euo pipefail
USER_NAME=jackson-hao
HOME_DIR=/home/$USER_NAME
HERMES="$HOME_DIR/.hermes/hermes-agent/.hermes/bin/hermes"
SRC="$(cd "$(dirname "$0")" && pwd)"
BOTS_MEM=${BOTS_MEM:-4G}
SANDBOX_IMAGE=${SANDBOX_IMAGE:-python:3.12-slim}

[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }

echo "== bots.slice (${BOTS_MEM}) and Docker cgroup-parent"
cat > /etc/systemd/system/bots.slice <<EOF
[Unit]
Description=All bot sandboxes
[Slice]
MemoryMax=$BOTS_MEM
MemorySwapMax=0
EOF
python3 - <<'PY'
import json, os
p = "/etc/docker/daemon.json"
d = json.load(open(p)) if os.path.exists(p) else {}
d["cgroup-parent"] = "bots.slice"
json.dump(d, open(p, "w"), indent=2)
PY
systemctl daemon-reload
systemctl restart docker

echo "== sandbox firewall (sandboxes cannot reach the home network)"
cat > /usr/local/sbin/bot-sandbox-firewall <<'EOF'
#!/bin/bash
# Containers started by Docker live in 172.16.0.0/12. They may reach the internet, nothing private.
add() { iptables -C "$@" 2>/dev/null || iptables -I "$@"; }
for net in 10.0.0.0/8 192.168.0.0/16 100.64.0.0/10 169.254.0.0/16; do
  add DOCKER-USER -s 172.16.0.0/12 -d "$net" -j DROP
done
add INPUT -s 172.16.0.0/12 -j DROP   # nor this container's own services
# ...except name resolution: containers on the default bridge use the host's resolver (the Incus bridge address) directly
for proto in udp tcp; do add DOCKER-USER -s 172.16.0.0/12 -d 10.0.0.1 -p "$proto" --dport 53 -j ACCEPT; done
EOF
chmod 755 /usr/local/sbin/bot-sandbox-firewall
cat > /etc/systemd/system/bot-sandbox-firewall.service <<'EOF'
[Unit]
Description=Block bot sandboxes from private networks
After=docker.service
Requires=docker.service
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/bot-sandbox-firewall
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now bot-sandbox-firewall
docker pull "$SANDBOX_IMAGE" >/dev/null

echo "== bot-template profile"
as_user() { su - "$USER_NAME" -c "$*"; }
if [ ! -d "$HOME_DIR/.hermes/profiles/bot-template" ]; then
  as_user "$HERMES profile create bot-template --clone-from labbot --no-alias" >/dev/null
fi
for kv in "terminal.backend docker" "terminal.docker_image $SANDBOX_IMAGE" "terminal.container_persistent true" \
          "terminal.container_cpu 1" "terminal.container_memory 1024" "terminal.container_disk 5120" \
          "terminal.lifetime_seconds 300"; do
  set -- $kv
  as_user "$HERMES -p bot-template config set $1 $2" >/dev/null
done
for t in terminal file; do as_user "$HERMES -p bot-template tools enable $t --platform api_server" >/dev/null; done

echo "== bot-manager service"
install -d -m 755 /opt/bot-manager
[ "$SRC" = /opt/bot-manager ] || install -m 644 "$SRC/bot_manager.py" /opt/bot-manager/bot_manager.py
if [ ! -f /etc/bot-manager.env ]; then
  umask 077
  printf 'MANAGER_KEY=%s\nMAX_BOTS=5\nHERMES_HOME=%s/.hermes\n' "$(head -c 36 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 40)" "$HOME_DIR" > /etc/bot-manager.env
fi
chown root:"$USER_NAME" /etc/bot-manager.env; chmod 640 /etc/bot-manager.env
install -m 644 "$SRC/bot-manager.service" /etc/systemd/system/bot-manager.service  # works from /opt/bot-manager too
systemctl daemon-reload
systemctl enable bot-manager
systemctl restart bot-manager
sleep 2
systemctl is-active bot-manager
echo "done. The manager key is in /etc/bot-manager.env (MANAGER_KEY)."
