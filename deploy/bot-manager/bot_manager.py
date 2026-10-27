#!/usr/bin/env python3
"""bot-manager: creates, lists, trashes and removes the sandboxed Lab bots, and forwards their chats.

A bot is a Hermes profile (system prompt in its SOUL.md, its own API_SERVER_KEY) whose tools run in a Docker sandbox.
The phone only ever holds the manager key; the per-bot keys stay on the server. Python standard library only.

Lifecycle: create -> active -> (delete) trashed -> (restore) active | (purge) gone. A trashed bot has no sandbox and its
profile directory is moved out of Hermes' profiles, so the gateway no longer serves it; nothing is lost until purge.
The janitor never deletes data it does not recognise: unknown profiles are moved to the trash.
"""
import hmac
import http.client
import json
import logging
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

log = logging.getLogger("bot-manager")

ID_RE = re.compile(r"^b[0-9a-f]{10}$")
MAX_BODY = 10 * 1024 * 1024
CHAT_FIELDS = ("messages", "stream", "temperature", "max_tokens")


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


# ----------------------------------------------------------------------------------------------- configuration


class Config:
    def __init__(self, env=None):
        e = os.environ if env is None else env
        self.manager_key = e.get("MANAGER_KEY", "")
        self.max_bots = int(e.get("MAX_BOTS", "5"))
        self.max_trash = int(e.get("MAX_TRASH", "20"))
        self.hermes_home = e.get("HERMES_HOME", os.path.expanduser("~/.hermes"))
        self.hermes_bin = e.get("HERMES_BIN", os.path.join(self.hermes_home, "hermes-agent/.hermes/bin/hermes"))
        self.hermes_url = e.get("HERMES_URL", "http://127.0.0.1:8642")
        self.template = e.get("TEMPLATE_PROFILE", "bot-template")
        self.helper_image = e.get("HELPER_IMAGE", "python:3.12-slim")
        self.host = e.get("LISTEN_HOST", "127.0.0.1")
        self.port = int(e.get("LISTEN_PORT", "8650"))
        self.janitor_seconds = int(e.get("JANITOR_SECONDS", "300"))

    @property
    def profiles(self):
        return os.path.join(self.hermes_home, "profiles")

    @property
    def state_dir(self):
        return os.path.join(self.hermes_home, "bot-manager")

    @property
    def trash(self):
        return os.path.join(self.state_dir, "trash")

    def check(self):
        if len(self.manager_key) < 24:
            raise SystemExit("MANAGER_KEY must be at least 24 characters")


# ----------------------------------------------------------------------------------------------- registry


class Registry:
    """The list of bots, one JSON file written atomically (with one backup copy)."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.bots = {}
        self._load()

    def _load(self):
        for p in (self.path, self.path + ".bak"):
            try:
                with open(p) as f:
                    self.bots = json.load(f)["bots"]
                return
            except (OSError, ValueError, KeyError):
                continue
        self.bots = {}

    def save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        if os.path.exists(self.path):
            shutil.copy2(self.path, self.path + ".bak")
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"bots": self.bots}, f, indent=1)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)

    def count(self, state):
        return sum(1 for b in self.bots.values() if b["state"] == state)


# ----------------------------------------------------------------------------------------------- backend (Hermes, Docker)


class Backend:
    """Everything that touches Hermes and Docker. Tests replace it with a fake."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.env = dict(os.environ, HERMES_HOME=cfg.hermes_home)

    def _run(self, args, timeout=120):
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=self.env)
        if r.returncode != 0:
            raise RuntimeError(f"{' '.join(args[:4])} failed: {(r.stderr or r.stdout).strip()[-300:]}")
        return r.stdout

    def create_profile(self, profile):
        self._run([self.cfg.hermes_bin, "profile", "create", profile, "--clone-from", self.cfg.template, "--no-alias"])

    def delete_profile(self, profile):
        self._run([self.cfg.hermes_bin, "profile", "delete", profile, "-y"])

    def _containers_under(self, path):
        ids = self._run(["docker", "ps", "-a", "-q"]).split()
        if not ids:
            return []
        out = self._run(["docker", "inspect", "--format", "{{.Id}} {{.State.Running}} {{range .Mounts}}{{.Source}} {{end}}"] + ids)
        found = []
        prefix = path.rstrip("/") + "/"
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 2 and any(s.startswith(prefix) or s == path for s in parts[2:]):
                found.append((parts[0], parts[1] == "true"))
        return found

    def remove_sandboxes(self, path):
        ids = [i for i, _ in self._containers_under(path)]
        if ids:
            self._run(["docker", "rm", "-f"] + ids)
        return len(ids)

    def sandbox_running(self, path):
        return any(running for _, running in self._containers_under(path))

    def sandbox_dirs(self):
        """Every directory under profiles/ that some container mounts from, as {profile: n}."""
        ids = self._run(["docker", "ps", "-a", "-q"]).split()
        if not ids:
            return {}
        out = self._run(["docker", "inspect", "--format", "{{range .Mounts}}{{.Source}} {{end}}", ] + ids)
        base = self.cfg.profiles.rstrip("/") + "/"
        found = {}
        for src in out.split():
            if src.startswith(base):
                name = src[len(base):].split("/", 1)[0]
                found[name] = found.get(name, 0) + 1
        return found

    def fix_owner(self, path):
        """Sandboxes write as root; give the files back so they can be removed."""
        self._run(["docker", "run", "--rm", "-v", f"{path}:/d", self.cfg.helper_image, "chown", "-R",
                   f"{os.getuid()}:{os.getgid()}", "/d"], timeout=300)

    def gateway_serves(self, profile, key):
        try:
            c = http.client.HTTPConnection(*self._host_port(), timeout=8)
            c.request("GET", f"/p/{profile}/v1/models", headers={"Authorization": f"Bearer {key}"})
            return c.getresponse().status == 200
        except OSError:
            return False

    def _host_port(self):
        u = urlsplit(self.cfg.hermes_url)
        return u.hostname, u.port or 80


# ----------------------------------------------------------------------------------------------- the manager


def read_env_value(path, name):
    try:
        with open(path) as f:
            for line in f:
                if line.startswith(name + "="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return ""


class Manager:
    def __init__(self, cfg, backend=None):
        self.cfg = cfg
        self.backend = backend or Backend(cfg)
        os.makedirs(cfg.trash, exist_ok=True)
        self.reg = Registry(os.path.join(cfg.state_dir, "registry.json"))

    # -- paths
    def profile_dir(self, bid):
        return os.path.join(self.cfg.profiles, bid)

    def trash_dir(self, bid):
        return os.path.join(self.cfg.trash, bid)

    def key_of(self, bid):
        return read_env_value(os.path.join(self.profile_dir(bid), ".env"), "API_SERVER_KEY")

    # -- validation
    @staticmethod
    def _clean(spec, partial=False):
        out = {}
        rules = {"name": (1, 40), "role": (0, 200), "systemPrompt": (0, 8000)}
        for k, (lo, hi) in rules.items():
            if k in spec:
                v = spec[k]
                if not isinstance(v, str) or not (lo <= len(v.strip()) <= hi):
                    raise ApiError(400, "bad_request", f"{k} must be a string of {lo}-{hi} characters")
                out[k] = v.strip()
            elif k == "name" and not partial:
                raise ApiError(400, "bad_request", "name is required")
        if "color" in spec:
            c = spec["color"]
            if not isinstance(c, int) or isinstance(c, bool) or not (0 <= c <= 0xFFFFFFFF):
                raise ApiError(400, "bad_request", "color must be an ARGB integer")
            out["color"] = c
        return out

    # -- views
    def _view(self, b, with_state=True):
        v = {k: b.get(k) for k in ("id", "name", "role", "color", "systemPrompt", "createdAt")}
        if b["state"] == "trashed":
            v["trashedAt"] = b.get("trashedAt")
        elif with_state:
            try:
                v["sandbox"] = "running" if self.backend.sandbox_running(self.profile_dir(b["id"])) else "idle"
            except Exception:
                v["sandbox"] = "unknown"
        return v

    def list_bots(self):
        with self.reg.lock:
            bots = sorted(self.reg.bots.values(), key=lambda b: b["createdAt"])
            return {
                "bots": [self._view(b) for b in bots if b["state"] == "active"],
                "trash": [self._view(b) for b in bots if b["state"] == "trashed"],
                "limit": self.cfg.max_bots,
            }

    def _get(self, bid, state):
        b = self.reg.bots.get(bid)
        if not b or b["state"] != state:
            raise ApiError(404, "not_found", "no such bot")
        return b

    # -- create
    def create(self, spec):
        data = self._clean(spec)
        with self.reg.lock:
            if self.reg.count("active") + self.reg.count("creating") >= self.cfg.max_bots:
                raise ApiError(409, "limit_reached", f"at most {self.cfg.max_bots} bots; delete one first")
            bid = "b" + secrets.token_hex(5)
            now = int(time.time())
            entry = {"id": bid, "state": "creating", "role": "", "systemPrompt": "", "color": 0xFF5546E0,
                     "createdAt": now, **data}
            self.reg.bots[bid] = entry
            self.reg.save()
        try:
            self.backend.create_profile(bid)
            pdir = self.profile_dir(bid)
            key = secrets.token_urlsafe(32)[:40]
            model_key = read_env_value(os.path.join(self.cfg.profiles, self.cfg.template, ".env"), "DEEPSEEK_API_KEY")
            env_path = os.path.join(pdir, ".env")
            fd = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                if model_key:
                    f.write(f"DEEPSEEK_API_KEY={model_key}\n")
                f.write(f"API_SERVER_KEY={key}\n")
            with open(os.path.join(pdir, "SOUL.md"), "w") as f:
                f.write(entry["systemPrompt"] or f"You are {entry['name']}.")
            ok = False
            for _ in range(10):
                if self.backend.gateway_serves(bid, key):
                    ok = True
                    break
                time.sleep(1)
            if not ok:
                raise RuntimeError("the gateway did not pick up the new profile")
        except Exception as e:
            log.error("create %s failed: %s", bid, e)
            self._discard(bid)
            raise ApiError(500, "create_failed", "could not create the bot") from e
        with self.reg.lock:
            entry["state"] = "active"
            self.reg.save()
        log.info("created %s (%s)", bid, entry["name"])
        return self._view(entry)

    def _discard(self, bid):
        """Remove a half-made bot completely (it never held user data)."""
        try:
            self.backend.remove_sandboxes(self.profile_dir(bid))
            if os.path.isdir(self.profile_dir(bid)):
                self.backend.fix_owner(self.profile_dir(bid))
                self.backend.delete_profile(bid)
        except Exception as e:
            log.error("discard %s: %s", bid, e)
        with self.reg.lock:
            self.reg.bots.pop(bid, None)
            self.reg.save()

    # -- update
    def update(self, bid, spec):
        data = self._clean(spec, partial=True)
        with self.reg.lock:
            b = self._get(bid, "active")
            b.update(data)
            if "systemPrompt" in data:
                with open(os.path.join(self.profile_dir(bid), "SOUL.md"), "w") as f:
                    f.write(b["systemPrompt"] or f"You are {b['name']}.")
            self.reg.save()
            return self._view(b)

    # -- trash / restore / purge
    def trash(self, bid):
        with self.reg.lock:
            b = self._get(bid, "active")
            if self.reg.count("trashed") >= self.cfg.max_trash:
                raise ApiError(409, "trash_full", "the trash is full; delete something from it first")
            self.backend.remove_sandboxes(self.profile_dir(bid))
            os.replace(self.profile_dir(bid), self.trash_dir(bid))
            b["state"], b["trashedAt"] = "trashed", int(time.time())
            self.reg.save()
        log.info("trashed %s", bid)

    def restore(self, bid):
        with self.reg.lock:
            b = self._get(bid, "trashed")
            if self.reg.count("active") + self.reg.count("creating") >= self.cfg.max_bots:
                raise ApiError(409, "limit_reached", f"at most {self.cfg.max_bots} bots; delete one first")
            os.replace(self.trash_dir(bid), self.profile_dir(bid))
            b["state"] = "active"
            b.pop("trashedAt", None)
            self.reg.save()
            log.info("restored %s", bid)
            return self._view(b)

    def purge(self, bid):
        with self.reg.lock:
            b = self.reg.bots.get(bid)
            if b and b["state"] == "active":
                raise ApiError(409, "not_in_trash", "move the bot to the trash first")
            self._get(bid, "trashed")
            os.replace(self.trash_dir(bid), self.profile_dir(bid))
            try:
                self.backend.remove_sandboxes(self.profile_dir(bid))
                self.backend.fix_owner(self.profile_dir(bid))
                self.backend.delete_profile(bid)
            except Exception as e:
                log.error("purge %s failed: %s", bid, e)
                if os.path.isdir(self.profile_dir(bid)):
                    os.replace(self.profile_dir(bid), self.trash_dir(bid))
                raise ApiError(500, "purge_failed", "could not remove the bot completely; it is still in the trash") from e
            self.reg.bots.pop(bid, None)
            self.reg.save()
            log.info("purged %s", bid)

    # -- janitor
    def janitor(self):
        """Reconcile registry, profiles, trash and sandboxes. Never deletes unknown data: it quarantines it."""
        with self.reg.lock:
            now = time.time()
            for bid, b in list(self.reg.bots.items()):
                if b["state"] == "creating" and now - b["createdAt"] > 600:
                    log.warning("janitor: dropping unfinished creation %s", bid)
                    self._discard(bid)
                elif b["state"] == "active" and not os.path.isdir(self.profile_dir(bid)):
                    log.warning("janitor: %s has no profile any more, dropping it", bid)
                    self.reg.bots.pop(bid)
                    self.reg.save()
                elif b["state"] == "trashed" and not os.path.isdir(self.trash_dir(bid)):
                    log.warning("janitor: trash entry %s has no data, dropping it", bid)
                    self.reg.bots.pop(bid)
                    self.reg.save()
            try:
                for name in sorted(os.listdir(self.cfg.profiles)):
                    if ID_RE.match(name) and name not in self.reg.bots:
                        log.warning("janitor: unknown profile %s, moving it to the trash", name)
                        self.backend.remove_sandboxes(self.profile_dir(name))
                        dest = self.trash_dir(name)
                        if not os.path.exists(dest):
                            os.replace(self.profile_dir(name), dest)
                            self.reg.bots[name] = {"id": name, "state": "trashed", "name": f"unknown {name}", "role": "",
                                                   "systemPrompt": "", "color": 0xFF9E9E9E, "createdAt": int(now),
                                                   "trashedAt": int(now)}
                            self.reg.save()
            except OSError as e:
                log.error("janitor: %s", e)
            try:
                for name in self.backend.sandbox_dirs():
                    b = self.reg.bots.get(name)
                    if ID_RE.match(name) and (not b or b["state"] != "active"):
                        log.warning("janitor: removing sandbox of %s", name)
                        self.backend.remove_sandboxes(self.profile_dir(name))
            except Exception as e:
                log.error("janitor sandboxes: %s", e)

    def run_janitor(self):
        while True:
            try:
                self.janitor()
            except Exception:
                log.exception("janitor failed")
            time.sleep(self.cfg.janitor_seconds)


# ----------------------------------------------------------------------------------------------- HTTP


def make_handler(mgr):
    cfg = mgr.cfg
    bot_re = re.compile(r"^/m/v1/bots/(b[0-9a-f]{10})(/restore|/chat/completions)?$")

    class Handler(BaseHTTPRequestHandler):
        server_version = "bot-manager"
        protocol_version = "HTTP/1.0"

        def log_message(self, fmt, *args):
            log.info("%s %s", self.address_string(), fmt % args)

        # -- plumbing
        def _json(self, status, obj):
            body = json.dumps(obj).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _error(self, e):
            self._json(e.status, {"error": {"code": e.code, "message": e.message}})

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > MAX_BODY:
                raise ApiError(413, "too_large", "request body too large")
            if n == 0:
                return {}
            try:
                data = json.loads(self.rfile.read(n))
            except ValueError:
                raise ApiError(400, "bad_request", "invalid JSON")
            if not isinstance(data, dict):
                raise ApiError(400, "bad_request", "expected a JSON object")
            return data

        def _authed(self):
            got = self.headers.get("Authorization", "")
            if not hmac.compare_digest(got.encode(), f"Bearer {cfg.manager_key}".encode()):
                time.sleep(0.4)
                raise ApiError(401, "unauthorized", "invalid manager key")

        def _route(self, method):
            try:
                path = urlsplit(self.path).path
                if method == "GET" and path == "/m/v1/health":
                    return self._json(200, {"ok": True})
                if path != "/m/v1/bots" and not bot_re.match(path):
                    raise ApiError(404, "not_found", "not found")
                self._authed()
                if path == "/m/v1/bots":
                    if method == "GET":
                        return self._json(200, mgr.list_bots())
                    if method == "POST":
                        return self._json(201, mgr.create(self._body()))
                    raise ApiError(405, "method_not_allowed", "method not allowed")
                bid, tail = bot_re.match(path).groups()
                if tail == "/restore" and method == "POST":
                    return self._json(200, mgr.restore(bid))
                if tail == "/chat/completions" and method == "POST":
                    return self._chat(bid, self._body())
                if tail is None and method == "PATCH":
                    return self._json(200, mgr.update(bid, self._body()))
                if tail is None and method == "DELETE":
                    # active bot -> trash; a trashed bot -> gone for good
                    if self.headers.get("X-Purge") == "1" or urlsplit(self.path).query == "purge=1":
                        mgr.purge(bid)
                    else:
                        mgr.trash(bid)
                    return self._json(200, {"ok": True})
                raise ApiError(405, "method_not_allowed", "method not allowed")
            except ApiError as e:
                self._error(e)
            except Exception:
                log.exception("unhandled error")
                self._error(ApiError(500, "internal", "internal error"))

        do_GET = lambda self: self._route("GET")
        do_POST = lambda self: self._route("POST")
        do_PATCH = lambda self: self._route("PATCH")
        do_DELETE = lambda self: self._route("DELETE")

        # -- chat
        def _chat(self, bid, body):
            with mgr.reg.lock:
                mgr._get(bid, "active")
            msgs = body.get("messages")
            if not isinstance(msgs, list) or not msgs:
                raise ApiError(400, "bad_request", "messages is required")
            clean = []
            for m in msgs:
                if not isinstance(m, dict) or m.get("role") not in ("user", "assistant", "system"):
                    raise ApiError(400, "bad_request", "bad message")
                if m["role"] != "system":  # the bot's prompt lives on the server
                    clean.append({"role": m["role"], "content": m.get("content", "")})
            if not clean:
                raise ApiError(400, "bad_request", "no user message")
            payload = {k: body[k] for k in CHAT_FIELDS if k in body}
            payload.update(messages=clean, model="hermes-agent")
            key = mgr.key_of(bid)
            if not key:
                raise ApiError(500, "no_key", "the bot has no key")
            host, port = mgr.backend._host_port()
            conn = http.client.HTTPConnection(host, port, timeout=300)
            try:
                conn.request("POST", f"/p/{bid}/v1/chat/completions", json.dumps(payload),
                             {"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
                resp = conn.getresponse()
            except OSError:
                raise ApiError(502, "upstream", "Hermes is not reachable")
            self.send_response(resp.status)
            self.send_header("Content-Type", resp.getheader("Content-Type", "application/json"))
            if payload.get("stream"):
                self.send_header("Cache-Control", "no-cache")
                self.send_header("X-Accel-Buffering", "no")
            self.end_headers()
            try:
                while True:
                    chunk = resp.read1(8192)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass  # the phone went away; closing the upstream connection stops the generation
            finally:
                conn.close()

    return Handler


def serve(cfg):
    cfg.check()
    mgr = Manager(cfg)
    threading.Thread(target=mgr.run_janitor, daemon=True).start()
    srv = ThreadingHTTPServer((cfg.host, cfg.port), make_handler(mgr))
    srv.daemon_threads = True
    log.info("listening on %s:%s (max %s bots)", cfg.host, cfg.port, cfg.max_bots)
    srv.serve_forever()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, stream=sys.stderr, format="%(levelname)s %(message)s")
    serve(Config())
