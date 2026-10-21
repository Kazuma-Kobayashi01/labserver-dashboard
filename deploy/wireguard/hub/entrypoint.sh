#!/bin/sh
# WireGuard hub: brings up wg0 from /config/wg0.conf and stays in the foreground.
set -eu
wg-quick up /config/wg0.conf
trap 'wg-quick down /config/wg0.conf; exit 0' TERM INT
while :; do sleep 3600 & wait $!; done
