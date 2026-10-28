#!/usr/bin/env python3
"""Hermes `pre_tool_call` hook for the app's bots: a command that downloads, installs or updates software, or deletes files
is sent to the human-approval gate, so the app asks the user first (Allow once / for this chat / always / Deny).
Everything else runs as before: writing and reading files, drawing pictures, running code.

Wire protocol (Hermes shell hooks): a JSON payload on stdin; on stdout either nothing (go ahead) or
{"action": "approve", "message": <what is asked>, "rule_key": "lablink-<kind>"}. The rule key is the kind of action, so
"allow for this chat" covers every download (say) in that chat, and nothing else.

It also keeps a note of every command for the app's command window: the full command before it runs and its output,
exit code and time once done (post_tool_call), as `$HERMES_HOME/lablink-tools/<tool call id>.start.json` / `.done.json`.
The bot-manager reads them as the reply streams by and forwards them to the app.

Commands that run a script file (`bash x.sh`, `python3 x.py`) are judged by the script too: it is read from the profile's
sandbox folders. This is a guard against accidents and a model going its own way, not a wall: obfuscated code can slip
through, which is why the sandbox itself stays the security boundary.
"""
import glob
import json
import os
import re
import sys
import time

KINDS = ("delete", "install", "download")

_W = r"(?:^|[\s;&|()`$]|\b)"   # start of a command word

PATTERNS = {
    "install": [
        r"\b(?:pip3?|pipx)\s+(?:install|download)\b",
        r"\bpython3?(?:\.\d+)?\s+-m\s+pip\s+(?:install|download)\b",
        r"\buv\s+(?:pip\s+install|add|sync|tool\s+install|run\s+--with)\b",
        r"\bpoetry\s+(?:add|install|update)\b",
        r"\b(?:conda|mamba|micromamba)\s+(?:install|update|create|upgrade)\b",
        r"\b(?:npm|pnpm|yarn|bun)\s+(?:install|i|add|update|upgrade|ci)\b",
        r"\b(?:npx|bunx|pnpx)\s+\S",
        r"\bapt(?:-get)?\s+(?:-\S+\s+)*(?:install|update|upgrade|dist-upgrade|full-upgrade)\b",
        r"\b(?:apk)\s+(?:add|update|upgrade)\b",
        r"\b(?:yum|dnf|zypper|pacman)\s+(?:-\S+\s+)*(?:install|update|upgrade|-S\w*)\b",
        r"\bdpkg\s+-i\b",
        r"\bgem\s+install\b",
        r"\bcargo\s+(?:install|update)\b",
        r"\bgo\s+(?:get|install)\b",
        r"\bbrew\s+(?:install|upgrade|update)\b",
        r"\brustup\b",
        r"\b(?:sdkmanager|nvm\s+install|pyenv\s+install)\b",
    ],
    "download": [
        _W + r"(?:curl|wget|aria2c|axel)\b",
        r"\bgit\s+(?:clone|pull|fetch|submodule\s+update|lfs\s+pull)\b",
        r"\b(?:scp|sftp|ftp|rsync)\s+\S*\S+:\S*",
        r"\bdocker\s+pull\b",
        r"\b(?:huggingface-cli|hf)\s+download\b",
        r"\b(?:yt-dlp|youtube-dl|gallery-dl)\b",
        r"\burlretrieve\b|\burllib\.request\b|\burlopen\s*\(",
        r"\brequests\.(?:get|post|request)\s*\(|\bhttpx\.(?:get|stream|Client)\b|\baiohttp\b",
        r"\bsnapshot_download\b|\bhf_hub_download\b",
    ],
    "delete": [
        _W + r"rm\s",
        _W + r"rm$",
        r"\b(?:rmdir|unlink|shred)\b",
        r"\bfind\b[^|;&]*\s-(?:delete|exec\s+rm)\b",
        r"\bgit\s+(?:clean|reset\s+--hard|rm)\b",
        r"\btruncate\s+-s\b",
        r"\bos\.(?:remove|unlink|rmdir|removedirs)\s*\(|\bshutil\.rmtree\s*\(|\.unlink\s*\(|\.rmdir\s*\(",
        r"\bfs\.(?:rm|rmSync|unlink|unlinkSync|rmdir|rmdirSync)\s*\(",
    ],
}
_COMPILED = {k: [re.compile(p, re.I | re.M) for p in v] for k, v in PATTERNS.items()}
_SCRIPT = re.compile(r"\b(?:bash|sh|zsh|dash|python3?(?:\.\d+)?|node|perl|ruby|source|\.)\s+(?:-\S+\s+)*([^\s;&|<>]+\.(?:sh|bash|py|js|mjs|pl|rb))\b")


def kind_of(text):
    """The first kind of guarded action [text] does, or None."""
    for kind in KINDS:
        if any(p.search(text) for p in _COMPILED[kind]):
            return kind
    return None


def script_paths(command, home, cwd):
    """Where the scripts [command] runs live on this host, given the profile folder [home]. Sandbox paths map to the folders
    the container mounts: /workspace and /root (its home), and relative paths are tried against both."""
    found = []
    for m in _SCRIPT.finditer(command):
        p = m.group(1)
        tries = []
        if p.startswith("/workspace/"):
            tries += glob.glob(os.path.join(home, "sandboxes", "docker", "*", "workspace", p[len("/workspace/"):]))
        elif p.startswith("/root/.hermes/"):
            tries.append(os.path.join(home, p[len("/root/.hermes/"):]))
        elif p.startswith("/root/"):
            tries += glob.glob(os.path.join(home, "sandboxes", "docker", "*", "home", p[len("/root/"):]))
        elif not p.startswith("/"):
            for sub in ("workspace", "home"):
                tries += glob.glob(os.path.join(home, "sandboxes", "docker", "*", sub, p))
            if cwd:
                tries.append(os.path.join(cwd, p))
        real_home = os.path.realpath(home) + os.sep
        for t in tries:
            r = os.path.realpath(t)
            if r.startswith(real_home) and os.path.isfile(r):
                found.append(r)
    return found


def decide(payload, home):
    """The hook's answer for one tool call: None to let it run, or the approval request."""
    if payload.get("tool_name") != "terminal":
        return None
    args = payload.get("tool_input") or {}
    command = str(args.get("command") or "")
    if not command.strip():
        return None
    kind = kind_of(command)
    if kind is None and home:
        for path in script_paths(command, home, payload.get("cwd") or ""):
            try:
                with open(path, encoding="utf-8", errors="replace") as f:
                    kind = kind_of(f.read(512 * 1024))
            except OSError:
                continue
            if kind:
                break
    if kind is None:
        return None
    shown = command if len(command) <= 600 else command[:600] + "…"
    return {"action": "approve", "message": shown, "rule_key": f"lablink-{kind}"}


TOOL_DIR = "lablink-tools"
MAX_OUTPUT = 16_000


def _call_id(payload):
    cid = str((payload.get("extra") or {}).get("tool_call_id") or payload.get("tool_call_id") or "")
    return cid if re.match(r"^[A-Za-z0-9_.:-]{1,120}$", cid) else ""


def _write(home, name, data):
    folder = os.path.join(home, TOOL_DIR)
    os.makedirs(folder, exist_ok=True)
    tmp = os.path.join(folder, f".{name}.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp, os.path.join(folder, name))


def note(payload, home):
    """Records a terminal command (before) or its outcome (after) for the app's command window."""
    if payload.get("tool_name") != "terminal" or not home:
        return
    cid = _call_id(payload)
    if not cid:
        return
    event = payload.get("hook_event_name")
    if event == "pre_tool_call":
        _write(home, f"{cid}.start.json", {"command": str((payload.get("tool_input") or {}).get("command") or ""), "at": time.time()})
    elif event == "post_tool_call":
        extra = payload.get("extra") or {}
        raw = extra.get("result")
        out, code, err = raw, None, None
        if isinstance(raw, str):
            try:
                raw = json.loads(raw)
            except ValueError:
                pass
        if isinstance(raw, dict):
            out, code, err = raw.get("output"), raw.get("exit_code"), raw.get("error")
        out = "" if out is None else str(out)
        if len(out) > MAX_OUTPUT:  # keep the end: that is where errors and results usually are
            out = "…\n" + out[-MAX_OUTPUT:]
        _write(home, f"{cid}.done.json", {
            "output": out, "exitCode": code, "error": err or extra.get("error_message"),
            "durationMs": extra.get("duration_ms"), "status": extra.get("status"),
        })
    # notes nobody picked up (a reply that was cut off) go after a day
    folder = os.path.join(home, TOOL_DIR)
    try:
        cutoff = time.time() - 86400
        for e in os.scandir(folder):
            if e.is_file() and e.stat().st_mtime < cutoff:
                os.remove(e.path)
    except OSError:
        pass


def main():
    try:
        payload = json.load(sys.stdin)
    except ValueError:
        return 0
    home = os.environ.get("HERMES_HOME", "")
    try:
        note(payload, home)
    except Exception:  # noqa: BLE001 - the command window is a nicety; it must never stop a command
        pass
    if payload.get("hook_event_name", "pre_tool_call") != "pre_tool_call":
        return 0
    try:
        out = decide(payload, os.environ.get("HERMES_HOME", ""))
    except Exception as e:  # noqa: BLE001 - when unsure, ask rather than block or let through
        out = {"action": "approve", "message": f"(policy check failed: {e}) " + str((payload.get("tool_input") or {}).get("command", ""))[:400],
               "rule_key": "lablink-unknown"}
    if out:
        sys.stdout.write(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
