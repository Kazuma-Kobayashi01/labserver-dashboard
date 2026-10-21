#!/bin/bash
# Installs / removes LabLink's Touch ID privilege path. Must run as root (the app launches it through the
# macOS admin prompt after the user asks for it in Settings).
#   install.sh install <username> <app Resources dir> <user's wg conf>
#   install.sh uninstall
# Installed pieces (all root:wheel, nothing user-writable):
#   /usr/local/libexec/lablink/{lablink-helper,wg,wireguard-go}   /usr/local/etc/lablink/lablink.conf (600)
#   /etc/sudoers.d/lablink : NOPASSWD for exactly "lablink-helper up" and "lablink-helper down"
set -euo pipefail
LIB=/usr/local/libexec/lablink
ETC=/usr/local/etc/lablink
SUDOERS=/etc/sudoers.d/lablink

case "${1:-}" in
  install)
    USERNAME="$2"; RES="$3"; CONF_SRC="$4"
    [ "$(id -u)" = 0 ] || { echo "must run as root"; exit 1; }
    [[ "$USERNAME" =~ ^[a-zA-Z0-9._-]+$ ]] || { echo "bad username"; exit 1; }
    for f in lablink-helper wg wireguard-go; do [ -f "$RES/$f" ] || { echo "missing $RES/$f"; exit 1; }; done
    [ -f "$CONF_SRC" ] || { echo "missing $CONF_SRC"; exit 1; }
    install -d -o root -g wheel -m 755 /usr/local/libexec "$LIB" /usr/local/etc "$ETC"
    install -o root -g wheel -m 755 "$RES/lablink-helper" "$LIB/lablink-helper"
    install -o root -g wheel -m 755 "$RES/wg" "$LIB/wg"
    install -o root -g wheel -m 755 "$RES/wireguard-go" "$LIB/wireguard-go"
    install -o root -g wheel -m 600 "$CONF_SRC" "$ETC/lablink.conf"
    T=$(mktemp)
    printf '%s ALL=(root) NOPASSWD: %s/lablink-helper up, %s/lablink-helper down\n' "$USERNAME" "$LIB" "$LIB" > "$T"
    /usr/sbin/visudo -cf "$T" >/dev/null || { rm -f "$T"; echo "sudoers validation failed"; exit 1; }
    install -o root -g wheel -m 440 "$T" "$SUDOERS"; rm -f "$T"
    echo "installed"
    ;;
  uninstall)
    [ "$(id -u)" = 0 ] || { echo "must run as root"; exit 1; }
    "$LIB/lablink-helper" down >/dev/null 2>&1 || true
    rm -f "$SUDOERS"
    rm -rf "$LIB" "$ETC"
    echo "removed"
    ;;
  *) echo "usage: install.sh install <user> <resources> <conf> | uninstall" >&2; exit 2 ;;
esac
