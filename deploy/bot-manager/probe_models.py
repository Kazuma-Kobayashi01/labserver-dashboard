#!/usr/bin/env python3
"""Builds the catalog of models the bot-manager offers to the phone (~/.hermes/bot-manager/catalog.json).

It asks Hermes itself what this server can use, then checks that every model really answers:
  * ChatGPT (provider openai-codex): the account's live model list and the thinking levels Hermes knows for each model
    (read by a small helper module that is put into Hermes' source tree for the moment of the call and removed again,
    run with `hermes --run-module`);
  * DeepSeek: the models its API lists for our key, with the levels Hermes maps for DeepSeek V4.
Each model gets one tiny request through the restricted `labbot` profile with an explicit `provider` (Hermes ignores a bare
model name); a rejected model still answers HTTP 200, so the failure is read from `finish_reason` / the `hermes` block.
Run it again when accounts or Hermes change:
    incus exec hermes -- su - jackson-hao -c 'python3 /opt/bot-manager/probe_models.py'
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

HOME = os.environ.get("HERMES_HOME", os.path.expanduser("~/.hermes"))
URL = os.environ.get("HERMES_URL", "http://127.0.0.1:8642")
HERMES_BIN = os.environ.get("HERMES_BIN", os.path.join(HOME, "hermes-agent/.hermes/bin/hermes"))
PROBE_PROFILE = "labbot"
HELPER_MODULE = "_bm_catalog"

# Runs inside Hermes' own environment.
HELPER = '''
import json
from agent.reasoning_effort import route_supported_efforts
from hermes_cli.auth import resolve_codex_runtime_credentials
from hermes_cli.codex_models import _fetch_models_from_api
creds = resolve_codex_runtime_credentials(read_only=True)
ids = _fetch_models_from_api(creds["api_key"], creds.get("base_url"))
print("BM_JSON:" + json.dumps({"openai-codex": [{"id": i, "efforts": list(route_supported_efforts("openai-codex", i))} for i in ids],
                               "deepseek_efforts": ["none", "low", "medium", "high", "max"]}))
'''


def env_value(path, name):
    for line in open(path):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    return ""


def hermes_view():
    helper = os.path.join(HOME, "hermes-agent", "hermes_cli", HELPER_MODULE + ".py")
    with open(helper, "w") as f:
        f.write(HELPER)
    try:
        r = subprocess.run([HERMES_BIN, "--run-module", f"hermes_cli.{HELPER_MODULE}"], capture_output=True, text=True,
                           timeout=120, env=dict(os.environ, HERMES_HOME=HOME))
    finally:
        os.remove(helper)
    for line in r.stdout.splitlines():
        if line.startswith("BM_JSON:"):
            return json.loads(line[len("BM_JSON:"):])
    sys.exit("could not read Hermes' model list: " + (r.stderr or r.stdout)[-300:])


def deepseek_ids():
    key = env_value(os.path.join(HOME, "profiles", PROBE_PROFILE, ".env"), "DEEPSEEK_API_KEY")
    if not key:
        return []
    req = urllib.request.Request("https://api.deepseek.com/v1/models", headers={"Authorization": f"Bearer {key}"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return [m["id"] for m in json.load(r)["data"]]
    except Exception as e:
        print("DeepSeek model list failed:", e)
        return []


def pretty(model_id):
    """gpt-6.1-sol -> GPT-6.1 Sol, deepseek-v4-pro -> DeepSeek V4 Pro"""
    parts = model_id.split("-")
    if parts[0] == "gpt" and len(parts) > 1:
        return "GPT-" + parts[1] + "".join(" " + p.capitalize() for p in parts[2:])
    if parts[0] == "deepseek":
        return "DeepSeek " + " ".join(p.upper() if re.fullmatch(r"v\d+", p) else p.capitalize() for p in parts[1:])
    return model_id


def ask(k, provider, model):
    body = {"provider": provider, "model": model, "stream": False,
            "messages": [{"role": "user", "content": f"Reply with the single word: ok ({time.time():.3f})"}]}
    req = urllib.request.Request(f"{URL}/p/{PROBE_PROFILE}/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {k}", "Content-Type": "application/json"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            d = json.load(r)
        choice = d["choices"][0]
        text = (choice["message"]["content"] or "").strip()
        failed = choice.get("finish_reason") == "error" or (d.get("hermes") or {}).get("failed")
        return bool(text) and not failed, f"{time.time() - t:.0f}s", ((d.get("hermes") or {}).get("error") or text)[:100]
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}", e.read()[:160].decode("utf-8", "replace")
    except Exception as e:
        return False, "error", str(e)[:160]


def main():
    k = env_value(os.path.join(HOME, "profiles", PROBE_PROFILE, ".env"), "API_SERVER_KEY")
    if not k:
        sys.exit("no API_SERVER_KEY for the probe profile")
    view = hermes_view()
    candidates = [("openai-codex", "ChatGPT", [(m["id"], m["efforts"]) for m in view["openai-codex"] if not m["id"].endswith("-900k")]),
                  ("deepseek", "DeepSeek", [(i, view["deepseek_efforts"]) for i in deepseek_ids()])]
    catalog = {"generated": int(time.time()), "providers": []}
    for pid, pname, models in candidates:
        entry = {"id": pid, "name": pname, "models": []}
        for mid, efforts in models:
            ok, took, info = ask(k, pid, mid)
            print(f"{pid}::{mid:18} {'ok  ' if ok else 'FAIL'} {took:>8} {info}", flush=True)
            if ok:
                entry["models"].append({"id": mid, "name": pretty(mid), "efforts": efforts})
        if entry["models"]:
            catalog["providers"].append(entry)
    os.makedirs(os.path.join(HOME, "bot-manager"), exist_ok=True)
    out = os.path.join(HOME, "bot-manager", "catalog.json")
    json.dump(catalog, open(out, "w"), indent=1, ensure_ascii=False)
    print("wrote", out)


if __name__ == "__main__":
    main()
