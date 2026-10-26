#!/bin/bash
# Adds a device (for example the Android app) to the WireGuard overlay: one [Peer] on the VPS hub, plus the forwarding
# rules on the hub and on the home gateway (Incus container wg-gw) that let it reach the home network (10.0.0.0/24).
# Changes are written to the config files (so they survive restarts) AND applied live; both configs are backed up first.
#
#   ./add-device.sh [--dry-run] <overlay-ip> <public-key> <name>
#   ./add-device.sh 10.77.0.4 'BASE64PUBLICKEY=' phone
#
# Environment (defaults match this repo's README):
#   HUB_SSH=user@vps                      the VPS that runs the wg-hub container
#   HOME_SSH=user@home-server             the home server (runs the Incus container wg-gw)
#   SSH_OPTS / HUB_SSH_OPTS / HOME_SSH_OPTS   extra ssh flags for both / the hub / the home server
#   HUB_CONF=/opt/wg-hub/config/wg0.conf  HUB_CONTAINER=wg-hub  GW_CONTAINER=wg-gw
#
# The rules are idempotent (`-C || -A`), so running the script twice, or restarting wg-quick, never piles up duplicates.
set -euo pipefail

DRY=0
if [ "${1:-}" = "--dry-run" ]; then DRY=1; shift; fi
[ $# -eq 3 ] || { sed -n '2,12p' "$0"; exit 2; }
IP=$1; PUB=$2; NAME=$3

HUB_SSH=${HUB_SSH:?set HUB_SSH, e.g. user@your-vps}
HOME_SSH=${HOME_SSH:?set HOME_SSH, e.g. user@your-home-server}
SSH_OPTS=${SSH_OPTS:-"-o BatchMode=yes -o ConnectTimeout=10"}
HUB_SSH_OPTS=${HUB_SSH_OPTS:-$SSH_OPTS}
HOME_SSH_OPTS=${HOME_SSH_OPTS:-$SSH_OPTS}
HUB_CONF=${HUB_CONF:-/opt/wg-hub/config/wg0.conf}
HUB_CONTAINER=${HUB_CONTAINER:-wg-hub}
GW_CONTAINER=${GW_CONTAINER:-wg-gw}
LAN=${LAN:-10.0.0.0/24}

[[ $IP =~ ^10\.77\.0\.([0-9]{1,3})$ ]] && [ "${BASH_REMATCH[1]}" -ge 4 ] && [ "${BASH_REMATCH[1]}" -le 254 ] \
  || { echo "overlay ip must be 10.77.0.4 .. 10.77.0.254 (.1 hub, .2 home gateway, .3 Mac)" >&2; exit 2; }
[[ $PUB =~ ^[A-Za-z0-9+/]{43}=$ ]] || { echo "that is not a WireGuard public key" >&2; exit 2; }
[[ $NAME =~ ^[A-Za-z0-9_-]+$ ]] || { echo "name: letters, digits, - and _ only" >&2; exit 2; }

run() { # run <ssh-target> <ssh-options> <script>   (a dry run only prints the script)
  if [ $DRY -eq 1 ]; then printf '\n--- on %s ---\n%s\n' "$1" "$3"; else ssh $2 "$1" "$3"; fi
}

# hub: rules go in [Interface] (before the first [Peer]); the peer goes at the end
HUB_RULES="PostUp = iptables -C FORWARD -i wg0 -o wg0 -s $IP -d $LAN -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wg0 -o wg0 -s $IP -d $LAN -j ACCEPT
PostUp = iptables -C FORWARD -i wg0 -o wg0 -s $LAN -d $IP -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wg0 -o wg0 -s $LAN -d $IP -j ACCEPT"
HUB_PEER="[Peer]
# $NAME
PublicKey = $PUB
AllowedIPs = $IP/32"

run "$HUB_SSH" "$HUB_SSH_OPTS" "set -e
f=$HUB_CONF
grep -q '$PUB' \$f && { echo 'hub: this public key is already a peer, nothing to do'; exit 0; }
grep -q 'AllowedIPs = $IP/32' \$f && { echo 'hub: $IP is already taken'; exit 1; }
cp -p \$f \$f.bak-\$(date +%Y%m%d-%H%M%S)
awk -v rules='$HUB_RULES' '/^\\[Peer\\]/ && !done { print rules; print \"\"; done=1 } { print }' \$f > \$f.new
printf '\\n%s\\n' '$HUB_PEER' >> \$f.new
cat \$f.new > \$f && rm \$f.new
sudo docker exec $HUB_CONTAINER wg set wg0 peer '$PUB' allowed-ips $IP/32
sudo docker exec $HUB_CONTAINER sh -c \"iptables -C FORWARD -i wg0 -o wg0 -s $IP -d $LAN -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wg0 -o wg0 -s $IP -d $LAN -j ACCEPT; iptables -C FORWARD -i wg0 -o wg0 -s $LAN -d $IP -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wg0 -o wg0 -s $LAN -d $IP -j ACCEPT\"
echo 'hub: $NAME ($IP) added'"

# gateway: forward the device into the home network, NATed behind the gateway's own address
GW_RULES="PostUp = iptables -C FORWARD -i wg0 -o eth0 -s $IP -d $LAN -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wg0 -o eth0 -s $IP -d $LAN -j ACCEPT
PostUp = iptables -C FORWARD -i eth0 -o wg0 -s $LAN -d $IP -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -A FORWARD -i eth0 -o wg0 -s $LAN -d $IP -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
PostUp = iptables -t nat -C POSTROUTING -s $IP -o eth0 -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s $IP -o eth0 -j MASQUERADE"

run "$HOME_SSH" "$HOME_SSH_OPTS" "set -e
g() { sudo incus exec $GW_CONTAINER -- \"\$@\"; }   # a function, not a string: works in bash and zsh login shells alike
g grep -q 'iptables -t nat -A POSTROUTING -s $IP ' /etc/wireguard/wg0.conf && { echo 'gateway: $IP already configured, nothing to do'; exit 0; }
g cp -p /etc/wireguard/wg0.conf /etc/wireguard/wg0.conf.bak-\$(date +%Y%m%d-%H%M%S)
g awk -v rules='$GW_RULES' '/^\\[Peer\\]/ && !done { print rules; print \"\"; done=1 } { print }' /etc/wireguard/wg0.conf | g tee /etc/wireguard/wg0.conf.new >/dev/null
g sh -c 'cat /etc/wireguard/wg0.conf.new > /etc/wireguard/wg0.conf && rm /etc/wireguard/wg0.conf.new'
g sh -c \"iptables -C FORWARD -i wg0 -o eth0 -s $IP -d $LAN -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wg0 -o eth0 -s $IP -d $LAN -j ACCEPT; iptables -C FORWARD -i eth0 -o wg0 -s $LAN -d $IP -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -A FORWARD -i eth0 -o wg0 -s $LAN -d $IP -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; iptables -t nat -C POSTROUTING -s $IP -o eth0 -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s $IP -o eth0 -j MASQUERADE\"
echo 'gateway: $IP allowed into $LAN'"

[ $DRY -eq 1 ] && echo && echo "(dry run: nothing was changed)" || true
