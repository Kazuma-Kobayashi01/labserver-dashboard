#!/usr/bin/env python3
"""End-to-end check on the server (inside the `hermes` container): create a bot, make its sandbox run commands, prove the
sandbox cannot reach the home network, then trash / restore / purge it and verify nothing is left behind.
  incus exec hermes -- su - jackson-hao -c 'python3 /opt/bot-manager/integration-check.py'
"""
import base64
import json
import os
import struct
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zlib

BASE = "http://127.0.0.1:8650"
HOME = os.path.expanduser("~/.hermes")
KEY = next(l.split("=", 1)[1].strip() for l in open("/etc/bot-manager.env") if l.startswith("MANAGER_KEY="))
failed = []


def call(method, path, body=None, timeout=200):
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        failed.append(name)


def ask(bid, text):
    s, r = call("POST", f"/m/v1/bots/{bid}/chat/completions", {"messages": [{"role": "user", "content": text}]})
    return r["choices"][0]["message"]["content"] if s == 200 else f"HTTP {s} {r}"


def docker_ids(bid):
    out = subprocess.run("docker ps -a -q", shell=True, capture_output=True, text=True).stdout.split()
    if not out:
        return []
    ins = subprocess.run(["docker", "inspect", "--format", "{{.Id}} {{range .Mounts}}{{.Source}} {{end}}"] + out,
                         capture_output=True, text=True).stdout
    return [l.split()[0] for l in ins.splitlines() if f"/{bid}/" in l]


s, r = call("POST", "/m/v1/bots", {"name": "Check bot", "role": "integration test", "systemPrompt": "Answer in one short line.", "color": 4283215584})
check("create", s == 201, str(r))
bid = r.get("id", "")
try:
    check("profile exists", os.path.isdir(f"{HOME}/profiles/{bid}"))
    ans = ask(bid, "Use the terminal tool to run exactly: echo hello-from-sandbox; hostname. Reply with only the output.")
    check("terminal runs in a sandbox", "hello-from-sandbox" in ans and docker_ids(bid), ans[:200])
    check("sandbox is listed as running", any(b["id"] == bid and b["sandbox"] == "running" for b in call("GET", "/m/v1/bots")[1]["bots"]))
    probe = ("python3 -c \"import urllib.request as u,sys\n"
             "for url in ['https://example.com','http://10.0.0.10/','http://10.0.0.1/','http://192.168.1.240/']:\n"
             "    try: print(url, 'REACHED', u.urlopen(url, timeout=5).status)\n"
             "    except Exception as e: print(url, 'REACHED' if getattr(e,'code',None) else 'BLOCKED', getattr(e,'code',''))\"")
    ans = ask(bid, "Use the terminal tool to run exactly this command and reply with only its raw output lines: " + probe)
    print("   network answer:", " | ".join(l.strip() for l in ans.splitlines() if l.strip())[:300])
    check("internet reachable", "example.com REACHED" in ans, ans[:300])
    check("home network blocked", all(f"{h} BLOCKED" in ans for h in ("10.0.0.10/", "10.0.0.1/", "192.168.1.240/")), ans[:300])
    # a file uploaded through the manager shows up inside the sandbox, and an inline image is seen by the model
    secret = "pineapple-4821"
    req = urllib.request.Request(BASE + f"/m/v1/bots/{bid}/files", method="POST", data=f"the secret word is {secret}\n".encode(),
                                 headers={"Authorization": f"Bearer {KEY}", "X-Filename": urllib.parse.quote("笔记 note.txt")})
    info = json.load(urllib.request.urlopen(req, timeout=30))
    check("upload returns the sandbox path", info["path"].endswith("/attachments/笔记 note.txt"), str(info))
    ans = ask(bid, f"Use the terminal tool to run exactly: cat '{info['path']}'. Reply with only the raw output.")
    check("the sandbox can read the uploaded file", secret in ans, ans[:200])

    def png(w, h, rgb):
        raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
        chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    url = "data:image/png;base64," + base64.b64encode(png(64, 64, (220, 30, 30))).decode()
    s2, r2 = call("POST", f"/m/v1/bots/{bid}/chat/completions", {"messages": [{"role": "user", "content": [
        {"type": "text", "text": "What single colour fills this image? One word."}, {"type": "image_url", "image_url": {"url": url}}]}]})
    answer = r2["choices"][0]["message"]["content"] if s2 == 200 else str(r2)
    check("the model sees an inline image", "red" in answer.lower() or "红" in answer, answer[:200])
    s3, _ = call("POST", f"/m/v1/bots/{bid}/chat/completions", {"messages": [{"role": "user", "content": [
        {"type": "image_url", "image_url": {"url": "http://10.0.0.10/x.png"}}]}]})
    check("a remote image URL is refused", s3 == 400, str(s3))
    s, _ = call("DELETE", f"/m/v1/bots/{bid}")
    check("trash", s == 200 and not os.path.isdir(f"{HOME}/profiles/{bid}") and os.path.isdir(f"{HOME}/bot-manager/trash/{bid}"))
    check("trash removed the sandbox", not docker_ids(bid))
    s, _ = call("POST", f"/m/v1/bots/{bid}/restore")
    check("restore", s == 200 and os.path.isdir(f"{HOME}/profiles/{bid}"))
    check("restored bot still answers", "HTTP" not in ask(bid, "Say ok."))
    call("DELETE", f"/m/v1/bots/{bid}")
    s, _ = call("DELETE", f"/m/v1/bots/{bid}?purge=1")
    check("purge", s == 200)
    check("nothing left", not os.path.exists(f"{HOME}/profiles/{bid}") and not os.path.exists(f"{HOME}/bot-manager/trash/{bid}") and not docker_ids(bid))
    lst = call("GET", "/m/v1/bots")[1]
    check("gone from the lists", all(b["id"] != bid for b in lst["bots"] + lst["trash"]))
finally:
    pass
print("\nALL PASSED" if not failed else f"\nFAILED: {failed}")
sys.exit(1 if failed else 0)
