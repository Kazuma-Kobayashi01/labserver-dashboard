#!/usr/bin/env python3
"""Convert $MIHOMO_CONF (default /etc/mihomo/config.yaml) from inline nodes to a proxy-provider subscription.

Reads the subscription URL from /etc/mihomo/subscription.url (or $SUB_URL), keeps every
rule and group, replaces the hard-coded node list with a provider, validates, hot-reloads
through the local controller, and restores the backup if the reload fails.

  sudo python3 to-subscription.py             # apply
  sudo python3 to-subscription.py --dry-run   # only write a test file and run `mihomo -t`
  sudo python3 to-subscription.py --lock-controller   # also bind the controller to 127.0.0.1
"""
import json, os, shutil, subprocess, sys, tempfile, time, urllib.request
import yaml

HOME = "/etc/mihomo"
CONF = os.environ.get("MIHOMO_CONF", f"{HOME}/config.yaml")
URL_FILE = f"{HOME}/subscription.url"
API = os.environ.get("MIHOMO_API", "http://127.0.0.1:9090")
SECRET = os.environ.get("MIHOMO_SECRET", "")
PROVIDER = "sub"


def api(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {SECRET}"} if SECRET else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}


def main():
    dry = "--dry-run" in sys.argv
    lock = "--lock-controller" in sys.argv

    url = os.environ.get("SUB_URL") or (open(URL_FILE).read().strip() if os.path.exists(URL_FILE) else "")
    if not url.startswith(("http://", "https://")):
        sys.exit(f"Put your subscription URL (one line) into {URL_FILE} first.")

    with open(CONF, encoding="utf-8") as f:
        cfg = yaml.safe_load(f)
    if "proxy-providers" in cfg:
        sys.exit("Config already uses proxy-providers; nothing to do.")

    nodes = {p["name"] for p in cfg.get("proxies", [])}
    for g in cfg["proxy-groups"]:
        g["proxies"] = [x for x in g.get("proxies", []) if x not in nodes]
        if not g["proxies"]:
            del g["proxies"]
        g["use"] = [PROVIDER]
    cfg.pop("proxies", None)

    new = {}
    for k, v in cfg.items():  # keep key order, place providers before groups
        if k == "proxy-groups":
            new["proxy-providers"] = {PROVIDER: {
                "type": "http", "url": url, "path": f"./providers/{PROVIDER}.yaml", "interval": 21600,
                "health-check": {"enable": True, "url": "http://1.1.1.1/generate_204", "interval": 600},
            }}
        new[k] = v
    if lock:
        new["external-controller"] = "127.0.0.1:9090"

    out = yaml.dump(new, allow_unicode=True, sort_keys=False, default_flow_style=False, width=4096)

    if dry:
        with tempfile.TemporaryDirectory() as d:
            for name in ("Country.mmdb", "geoip.dat", "geosite.dat"):
                if os.path.exists(f"{HOME}/{name}"):
                    os.symlink(f"{HOME}/{name}", f"{d}/{name}")
            with open(f"{d}/test.yaml", "w", encoding="utf-8") as f:
                f.write(out)
            r = subprocess.run(["mihomo", "-t", "-d", d, "-f", f"{d}/test.yaml"], capture_output=True, text=True)
            print(r.stdout + r.stderr)
            print("groups:", len(new["proxy-groups"]), "rules:", len(new["rules"]), "dry run OK" if r.returncode == 0 else "dry run FAILED")
            sys.exit(r.returncode)

    backup = f"{CONF}.bak-{time.strftime('%Y%m%d-%H%M')}"
    shutil.copy2(CONF, backup)
    print("backup:", backup)
    os.makedirs(f"{HOME}/providers", exist_ok=True)
    tmp = CONF + ".new"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(out)
    os.chmod(tmp, 0o640)

    r = subprocess.run(["mihomo", "-t", "-d", HOME, "-f", tmp], capture_output=True, text=True)
    if r.returncode != 0:
        os.remove(tmp)
        sys.exit("config test failed, nothing changed:\n" + r.stdout + r.stderr)

    os.replace(tmp, CONF)
    try:
        api("PUT", "/configs?force=true", {"path": CONF})
        time.sleep(2)
        info = api("GET", f"/providers/proxies/{PROVIDER}")
        count = len(info.get("proxies", []))
        if count == 0:
            raise RuntimeError("subscription loaded but contains no nodes")
    except Exception as e:  # restore the previous config
        shutil.copy2(backup, CONF)
        try:
            api("PUT", "/configs?force=true", {"path": CONF})
        except Exception:
            pass
        sys.exit(f"reload failed ({e}); restored {backup}")
    print(f"OK: subscription loaded, {count} nodes. Auto-updates every 6 hours.")
    if lock:
        print("Controller now binds 127.0.0.1:9090 -> run: sudo systemctl restart mihomo  (needed for the bind change)")


if __name__ == "__main__":
    main()
