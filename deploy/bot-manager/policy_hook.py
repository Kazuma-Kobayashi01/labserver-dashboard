#!/usr/bin/env python3
"""Hermes `pre_tool_call` hook for the app's bots: a command that downloads, installs or updates software, or deletes files
is sent to the human-approval gate, so the app asks the user first (Allow once / for this chat / always / Deny).
Everything else runs as before: writing and reading files, drawing pictures, running code.

Wire protocol (Hermes shell hooks): a JSON payload on stdin; on stdout either nothing (go ahead) or
{"action": "approve", "message": <what is asked>, "rule_key": "lablink-<kind>"}. The rule key is the kind of action, so
"allow for this chat" covers every download (say) in that chat, and nothing else.

Commands that run a script file (`bash x.sh`, `python3 x.py`) are judged by the script too: it is read from the profile's
sandbox folders. This is a guard against accidents and a model going its own way, not a wall: obfuscated code can slip
through, which is why the sandbox itself stays the security boundary.
"""
import glob
import json
import os
import re
import sys

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


def main():
    try:
        payload = json.load(sys.stdin)
    except ValueError:
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
