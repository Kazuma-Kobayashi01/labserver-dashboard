#!/usr/bin/env python3
"""Puts the app's safety rules into the Hermes profiles (run as the Hermes user, then restart hermes-gateway):

* every bot profile the app uses (the template new bots are cloned from, the sandbox bots, active or trashed, and the app's
  default profile) gets the `pre_tool_call` hook policy_hook.py: downloads, installs/updates and deletions ask the user
  in the app first;
* with --sandbox-labbot the default profile also moves into a Docker sandbox like the other bots (the template's terminal
  settings) and gets files and commands, so it can read and write what it is sent and draw pictures.

Every config.yaml it changes is backed up next to itself first; running it again changes nothing.

  python3 apply_policy.py --dry-run
  python3 apply_policy.py --sandbox-labbot
"""
import json
import os
import re
import shutil
import sys
import time

HOOK_MARK = "# LabLink policy"
HOOK_BLOCK = f"""
{HOOK_MARK}: downloads, installs/updates and deletions ask the user in the app first (deploy/bot-manager/policy_hook.py)
hooks:
  pre_tool_call:
    - matcher: "^terminal$"
      command: "python3 /opt/bot-manager/policy_hook.py"
      timeout: 20
      fail_closed: true
hooks_auto_accept: true
"""
APP_TOOLSETS = ["file", "memory", "session_search", "terminal", "todo", "vision", "web"]
TOP_KEY = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*:")


def add_hook(text):
    """[text] with the policy hook, or None when it already has one. A profile with hooks of its own is left alone."""
    if HOOK_MARK in text:
        return None
    if re.search(r"^hooks:", text, re.M) or re.search(r"^hooks_auto_accept:", text, re.M):
        raise ValueError("this profile already declares hooks; merge the LabLink hook by hand")
    return text.rstrip("\n") + "\n" + HOOK_BLOCK


def _block(lines, key):
    start = next((i for i, l in enumerate(lines) if l.startswith(key + ":")), None)
    if start is None:
        raise ValueError(f"no {key}: section")
    end = next((i for i in range(start + 1, len(lines)) if TOP_KEY.match(lines[i])), len(lines))
    return start, end


def use_template_terminal(text, template):
    """[text] with its `terminal:` section replaced by the template's (the Docker sandbox and its limits)."""
    lines, tl = text.splitlines(keepends=True), template.splitlines(keepends=True)
    s, e = _block(lines, "terminal")
    ts, te = _block(tl, "terminal")
    return "".join(lines[:s] + tl[ts:te] + lines[e:])


def set_api_toolsets(text, toolsets):
    """[text] with `platform_toolsets.api_server` set to [toolsets]."""
    lines = text.splitlines(keepends=True)
    s, e = _block(lines, "platform_toolsets")
    at = next((i for i in range(s, e) if lines[i].rstrip() == "  api_server:"), None)
    if at is None:
        raise ValueError("no platform_toolsets.api_server list")
    end = at + 1
    while end < e and lines[end].startswith("    - "):
        end += 1
    return "".join(lines[:at + 1] + [f"    - {t}\n" for t in toolsets] + lines[end:])


def check_yaml(text):
    try:
        import yaml  # noqa: PLC0415 - optional: Hermes' own Python has it, a bare system Python may not
    except ImportError:
        return
    yaml.safe_load(text)


def profiles(home, app_profiles):
    base = os.path.join(home, "profiles")
    out = [os.path.join(base, os.environ.get("TEMPLATE_PROFILE", "bot-template"))]
    out += [os.path.join(base, p) for p in app_profiles]
    try:
        with open(os.path.join(home, "bot-manager", "registry.json")) as f:
            reg = json.load(f)
    except (OSError, ValueError):
        reg = {}
    for bid, b in (reg.get("bots") or {}).items():
        where = os.path.join(home, "bot-manager", "trash", bid) if b.get("state") == "trashed" else os.path.join(base, bid)
        out.append(where)
    return [p for p in out if os.path.isfile(os.path.join(p, "config.yaml"))]


def write(path, text, dry, stamp):
    check_yaml(text)
    if dry:
        return
    shutil.copy2(path, f"{path}.bak-{stamp}-policy")
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    shutil.copymode(path, tmp)
    os.replace(tmp, path)


def main(argv):
    dry = "--dry-run" in argv
    home = os.environ.get("HERMES_HOME", os.path.expanduser("~/.hermes"))
    app_profiles = [p for p in os.environ.get("LOCAL_PROFILES", "labbot").split(",") if p]
    stamp = time.strftime("%Y%m%d-%H%M%S")
    changed = 0
    for prof in profiles(home, app_profiles):
        path = os.path.join(prof, "config.yaml")
        with open(path) as f:
            text = f.read()
        new = add_hook(text)
        if new is not None:
            write(path, new, dry, stamp)
            changed += 1
            print(("would add" if dry else "added") + " the policy hook:", prof)
    if "--sandbox-labbot" in argv:
        tpl = os.path.join(home, "profiles", os.environ.get("TEMPLATE_PROFILE", "bot-template"), "config.yaml")
        with open(tpl) as f:
            template = f.read()
        for p in app_profiles:
            path = os.path.join(home, "profiles", p, "config.yaml")
            with open(path) as f:
                text = f.read()
            new = set_api_toolsets(use_template_terminal(text, template), APP_TOOLSETS)
            if new != text:
                write(path, new, dry, stamp)
                changed += 1
                print(("would sandbox" if dry else "sandboxed") + f" {p}: Docker terminal, tools {', '.join(APP_TOOLSETS)}")
    print(f"{changed} change(s){' (dry run)' if dry else ''}; restart hermes-gateway to apply")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
