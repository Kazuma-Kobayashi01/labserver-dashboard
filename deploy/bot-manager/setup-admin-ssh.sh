#!/bin/bash
# Lets the admin bot (Lab bot, in the hermes container) log in to the host as `ssh labserver`. Run on the host, as
# jackson-hao, or from a Mac:   ssh home-away 'bash -s' < deploy/bot-manager/setup-admin-ssh.sh
# It makes a key of its own in the container, pins the host's key (only when it matches the host's own), and lets that key
# in from the container network only. Running it again changes nothing.
set -euo pipefail
C=(sudo incus exec hermes --user 1000 --group 1000 --env HOME=/home/jackson-hao --cwd /home/jackson-hao --)

"${C[@]}" sh -c 'install -d -m 700 ~/.ssh
test -f ~/.ssh/labserver_admin || ssh-keygen -q -t ed25519 -N "" -C labbot-admin -f ~/.ssh/labserver_admin
printf "Host labserver\n  HostName 10.0.0.1\n  User jackson-hao\n  IdentityFile ~/.ssh/labserver_admin\n  IdentitiesOnly yes\n  StrictHostKeyChecking yes\n  ServerAliveInterval 30\n" > ~/.ssh/config
chmod 600 ~/.ssh/config' </dev/null

host_fp=$(sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | cut -d' ' -f2)
# kept under the address: ssh looks a host key up by the HostName it connects to
scan=$("${C[@]}" ssh-keyscan -t ed25519 10.0.0.1 2>/dev/null </dev/null)
scan_fp=$(ssh-keygen -lf - <<<"$scan" | cut -d' ' -f2)
if [ "$host_fp" != "$scan_fp" ]; then
  echo "the key seen from the container ($scan_fp) is not the host's ($host_fp); stopping" >&2
  exit 1
fi
"${C[@]}" sh -c 'cat > ~/.ssh/known_hosts; chmod 644 ~/.ssh/known_hosts' <<<"$scan"

pub=$("${C[@]}" cat /home/jackson-hao/.ssh/labserver_admin.pub </dev/null)
if ! grep -qF "$pub" ~/.ssh/authorized_keys; then
  cp -p ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak-"$(date +%Y%m%d-%H%M)"-labbot
  echo "from=\"10.0.0.0/24\" $pub" >> ~/.ssh/authorized_keys
fi

"${C[@]}" ssh labserver 'echo "Lab bot reaches $(hostname)"; sudo -n true && echo "with sudo"' </dev/null
