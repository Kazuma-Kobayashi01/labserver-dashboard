"""Tests for bot_manager with a fake Hermes/Docker backend and a fake Hermes API.  Run: python3 -m unittest -v"""
import json
import os
import shutil
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import bot_manager as bm

KEY = "k" * 32


class FakeBackend:
    def __init__(self, cfg):
        self.cfg = cfg
        self.sandboxes = {}          # profile -> running
        self.serves = True
        self.fail_delete = False
        self.calls = []

    def create_profile(self, profile):
        self.calls.append(("create", profile))
        os.makedirs(os.path.join(self.cfg.profiles, profile))
        open(os.path.join(self.cfg.profiles, profile, "state.db"), "w").write("x")

    def delete_profile(self, profile):
        self.calls.append(("delete", profile))
        if self.fail_delete:
            raise RuntimeError("boom")
        shutil.rmtree(os.path.join(self.cfg.profiles, profile))

    def remove_sandboxes(self, path):
        self.sandboxes.pop(os.path.basename(path), None)
        return 0

    def sandbox_running(self, path):
        return self.sandboxes.get(os.path.basename(path), False)

    def sandbox_dirs(self):
        return {k: 1 for k in self.sandboxes}

    def fix_owner(self, path):
        pass

    container_files = {}   # path inside the running sandbox -> bytes

    def copy_out(self, path, container_path, dest):
        if not self.sandboxes.get(os.path.basename(path)) or container_path not in self.container_files:
            return False
        open(dest, "wb").write(self.container_files[container_path])
        return True

    def chat_only(self, path):
        self.calls.append(("chat_only", os.path.basename(path)))

    def shell_container(self, path):
        return "cid-" + os.path.basename(path) if os.path.basename(path) in self.sandboxes else None

    def gateway_serves(self, profile, key):
        return self.serves

    def _host_port(self):
        return "127.0.0.1", self.upstream_port


class Upstream(BaseHTTPRequestHandler):
    seen = []
    events = []
    reply = "pong"

    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Upstream.seen.append((self.path, self.headers.get("Authorization"), body))
        if self.path.endswith("/approval"):
            out = json.dumps({"ok": True, "resolved": 1}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream" if body.get("stream") else "application/json")
        self.end_headers()
        if body.get("stream") and Upstream.events:
            for part in Upstream.events:
                self.wfile.write(part.encode())
                self.wfile.flush()
        elif body.get("stream"):
            for part in ("data: one\n\n", "data: two\n\n", "data: [DONE]\n\n"):
                self.wfile.write(part.encode())
                self.wfile.flush()
        else:
            self.wfile.write(json.dumps({"choices": [{"message": {"content": Upstream.reply}}]}).encode())


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        env = {"MANAGER_KEY": KEY, "HERMES_HOME": self.tmp, "MAX_BOTS": "3", "MAX_TRASH": "2", "LISTEN_PORT": "0"}
        self.cfg = bm.Config(env)
        os.makedirs(self.cfg.profiles)
        os.makedirs(os.path.join(self.cfg.profiles, self.cfg.template))
        open(os.path.join(self.cfg.profiles, self.cfg.template, ".env"), "w").write("DEEPSEEK_API_KEY=model-key\n")
        os.makedirs(self.cfg.state_dir, exist_ok=True)
        json.dump({"generated": 1, "providers": [
            {"id": "deepseek", "name": "DeepSeek", "models": [{"id": "deepseek-flash", "name": "Flash", "efforts": ["none", "high"]}]},
            {"id": "openai-codex", "name": "ChatGPT", "models": [{"id": "gpt-5.6-sol", "name": "Sol", "efforts": ["low", "high", "xhigh"]}]},
        ]}, open(os.path.join(self.cfg.state_dir, "catalog.json"), "w"))
        self.fb = FakeBackend(self.cfg)
        self.up = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        self.fb.upstream_port = self.up.server_address[1]
        threading.Thread(target=self.up.serve_forever, daemon=True).start()
        Upstream.seen = []
        Upstream.reply = "pong"
        Upstream.events = []
        self.fb.container_files = {}
        self.mgr = bm.Manager(self.cfg, self.fb)
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), bm.make_handler(self.mgr))
        self.srv.daemon_threads = True
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.up.shutdown()
        self.up.server_close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def call(self, method, path, body=None, key=KEY):
        req = urllib.request.Request(self.base + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def make(self, name="Bot", **kw):
        s, b = self.call("POST", "/m/v1/bots", {"name": name, **kw})
        self.assertEqual(s, 201, b)
        return json.loads(b)


class Api(Base):
    def test_auth_and_unknown_paths(self):
        self.assertEqual(self.call("GET", "/m/v1/bots", key="wrong")[0], 401)
        self.assertEqual(self.call("GET", "/m/v1/nothing")[0], 404)
        self.assertEqual(self.call("GET", "/etc/passwd")[0], 404)
        self.assertEqual(self.call("GET", "/m/v1/health", key="")[0], 200)

    def test_create_writes_profile_files_and_lists(self):
        b = self.make("Alice", role="helper", systemPrompt="Be brief.", color=0xFF112233)
        pdir = os.path.join(self.cfg.profiles, b["id"])
        self.assertEqual(open(os.path.join(pdir, "SOUL.md")).read(), "Be brief.")
        env = open(os.path.join(pdir, ".env")).read()
        self.assertIn("DEEPSEEK_API_KEY=model-key", env)
        self.assertIn("API_SERVER_KEY=", env)
        self.assertEqual(oct(os.stat(os.path.join(pdir, ".env")).st_mode & 0o777), "0o600")
        listing = json.loads(self.call("GET", "/m/v1/bots")[1])
        self.assertEqual([x["name"] for x in listing["bots"]], ["Alice"])
        self.assertEqual(listing["limit"], 3)
        self.assertNotIn("API_SERVER_KEY", json.dumps(listing))

    def test_limit(self):
        for i in range(3):
            self.make(f"b{i}")
        s, body = self.call("POST", "/m/v1/bots", {"name": "extra"})
        self.assertEqual(s, 409)
        self.assertEqual(json.loads(body)["error"]["code"], "limit_reached")

    def test_validation(self):
        self.assertEqual(self.call("POST", "/m/v1/bots", {})[0], 400)
        self.assertEqual(self.call("POST", "/m/v1/bots", {"name": "x" * 41})[0], 400)
        self.assertEqual(self.call("POST", "/m/v1/bots", {"name": "ok", "color": "red"})[0], 400)

    def test_create_rolls_back_when_gateway_does_not_see_it(self):
        self.fb.serves = False
        bm.time.sleep = lambda s: None
        try:
            s, _ = self.call("POST", "/m/v1/bots", {"name": "ghost"})
        finally:
            bm.time.sleep = time.sleep
        self.assertEqual(s, 500)
        self.assertEqual(os.listdir(self.cfg.profiles), [self.cfg.template])
        self.assertEqual(json.loads(self.call("GET", "/m/v1/bots")[1])["bots"], [])

    def test_update_rewrites_soul(self):
        b = self.make("A", systemPrompt="one")
        s, _ = self.call("PATCH", f"/m/v1/bots/{b['id']}", {"systemPrompt": "two", "name": "B"})
        self.assertEqual(s, 200)
        self.assertEqual(open(os.path.join(self.cfg.profiles, b["id"], "SOUL.md")).read(), "two")

    def test_trash_restore_purge(self):
        b = self.make("A")
        pdir = os.path.join(self.cfg.profiles, b["id"])
        self.fb.sandboxes[b["id"]] = True
        self.assertEqual(self.call("DELETE", f"/m/v1/bots/{b['id']}")[0], 200)
        self.assertFalse(os.path.exists(pdir))                    # the gateway no longer serves it
        self.assertTrue(os.path.exists(os.path.join(self.cfg.trash, b["id"], "state.db")))   # data kept
        self.assertNotIn(b["id"], self.fb.sandboxes)             # sandbox gone
        listing = json.loads(self.call("GET", "/m/v1/bots")[1])
        self.assertEqual((len(listing["bots"]), len(listing["trash"])), (0, 1))
        self.assertEqual(self.call("POST", f"/m/v1/bots/{b['id']}/restore")[0], 200)
        self.assertTrue(os.path.exists(os.path.join(pdir, "state.db")))
        self.call("DELETE", f"/m/v1/bots/{b['id']}")
        self.assertEqual(self.call("DELETE", f"/m/v1/bots/{b['id']}?purge=1")[0], 200)
        self.assertEqual(os.listdir(self.cfg.profiles), [self.cfg.template])
        self.assertEqual(os.listdir(self.cfg.trash), [])
        self.assertEqual(json.loads(self.call("GET", "/m/v1/bots")[1])["trash"], [])

    def test_restore_and_purge_work_when_the_gateway_recreated_a_log_stub(self):
        for action in ("restore", "purge"):
            b = self.make(action)
            self.call("DELETE", f"/m/v1/bots/{b['id']}")
            stub = os.path.join(self.cfg.profiles, b["id"], "logs")
            os.makedirs(stub)
            open(os.path.join(stub, "agent.log"), "w").write("x")
            if action == "restore":
                self.assertEqual(self.call("POST", f"/m/v1/bots/{b['id']}/restore")[0], 200)
                self.assertTrue(os.path.exists(os.path.join(self.cfg.profiles, b["id"], "state.db")))
            else:
                self.assertEqual(self.call("DELETE", f"/m/v1/bots/{b['id']}?purge=1")[0], 200)
                self.assertFalse(os.path.exists(os.path.join(self.cfg.profiles, b["id"])))

    def test_a_chat_transcript_written_after_trashing_does_not_block_restore_or_purge(self):
        for action in ("restore", "purge"):
            b = self.make(action)
            self.call("DELETE", f"/m/v1/bots/{b['id']}")
            stub = os.path.join(self.cfg.profiles, b["id"], "sessions")
            os.makedirs(stub)
            open(os.path.join(stub, "api-late.jsonl"), "w").write("late chat")
            if action == "restore":
                self.assertEqual(self.call("POST", f"/m/v1/bots/{b['id']}/restore")[0], 200)
                home = os.path.join(self.cfg.profiles, b["id"])
                self.assertTrue(os.path.exists(os.path.join(home, "state.db")))
                # the late transcript is kept with the bot
                self.assertEqual(open(os.path.join(home, "sessions", "api-late.jsonl")).read(), "late chat")
            else:
                self.assertEqual(self.call("DELETE", f"/m/v1/bots/{b['id']}?purge=1")[0], 200)
                self.assertFalse(os.path.exists(os.path.join(self.cfg.profiles, b["id"])))
                self.assertFalse(os.path.exists(os.path.join(self.cfg.trash, b["id"])))

    def test_a_non_log_directory_in_the_way_is_never_deleted(self):
        b = self.make("A")
        self.call("DELETE", f"/m/v1/bots/{b['id']}")
        os.makedirs(os.path.join(self.cfg.profiles, b["id"]))
        keep = os.path.join(self.cfg.profiles, b["id"], "precious.txt")
        open(keep, "w").write("x")
        s, body = self.call("POST", f"/m/v1/bots/{b['id']}/restore")
        self.assertEqual((s, json.loads(body)["error"]["code"]), (409, "profile_dir_busy"))
        self.assertTrue(os.path.exists(keep))
        self.assertTrue(os.path.exists(os.path.join(self.cfg.trash, b["id"], "state.db")))

    def test_purge_failure_keeps_it_in_trash(self):
        b = self.make("A")
        self.call("DELETE", f"/m/v1/bots/{b['id']}")
        self.fb.fail_delete = True
        self.assertEqual(self.call("DELETE", f"/m/v1/bots/{b['id']}?purge=1")[0], 500)
        self.assertTrue(os.path.exists(os.path.join(self.cfg.trash, b["id"], "state.db")))
        self.assertEqual(len(json.loads(self.call("GET", "/m/v1/bots")[1])["trash"]), 1)

    def test_active_bot_cannot_be_purged_directly(self):
        b = self.make("A")
        s, body = self.call("DELETE", f"/m/v1/bots/{b['id']}?purge=1")
        self.assertEqual((s, json.loads(body)["error"]["code"]), (409, "not_in_trash"))
        self.assertTrue(os.path.exists(os.path.join(self.cfg.profiles, b["id"], "state.db")))   # nothing was touched

    def test_restore_respects_limit_and_trash_cap(self):
        ids = [self.make(f"b{i}")["id"] for i in range(3)]
        self.call("DELETE", f"/m/v1/bots/{ids[0]}")
        self.make("fourth")                                       # fills the freed slot
        self.assertEqual(self.call("POST", f"/m/v1/bots/{ids[0]}/restore")[0], 409)
        self.call("DELETE", f"/m/v1/bots/{ids[1]}")
        self.assertEqual(self.call("DELETE", f"/m/v1/bots/{ids[2]}")[0], 409)   # trash cap is 2

    def test_chat_forwards_with_the_bots_key_and_strips_extras(self):
        b = self.make("A", systemPrompt="server side")
        s, body = self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions", {
            "messages": [{"role": "system", "content": "evil"}, {"role": "user", "content": "hi"}],
            "tools": [{"type": "x"}], "model": "other", "stream": False, "temperature": 0.2})
        self.assertEqual(s, 200)
        self.assertEqual(json.loads(body)["choices"][0]["message"]["content"], "pong")
        path, auth, sent = Upstream.seen[-1]
        self.assertEqual(path, f"/p/{b['id']}/v1/chat/completions")
        self.assertEqual(auth, "Bearer " + bm.read_env_value(os.path.join(self.cfg.profiles, b["id"], ".env"), "API_SERVER_KEY"))
        self.assertEqual(sent["messages"], [{"role": "user", "content": "hi"}])
        self.assertNotIn("tools", sent)
        self.assertEqual(sent["model"], "hermes-agent")

    def media(self, bot, path, key=KEY):
        from urllib.parse import quote
        return self.call("GET", f"/m/v1/media?bot={quote(bot)}&path={quote(path)}", key=key)

    def test_media_maps_sandbox_paths_to_the_profile_and_serves_only_pictures(self):
        b = self.make("A")
        home = os.path.join(self.cfg.profiles, b["id"])
        png = b"\x89PNG\r\n\x1a\nfake"
        os.makedirs(os.path.join(home, "cache", "images"), exist_ok=True)
        open(os.path.join(home, "cache", "images", "chart.png"), "wb").write(png)
        box = os.path.join(home, "sandboxes", "docker", "profile_x", "workspace")
        os.makedirs(box, exist_ok=True)
        open(os.path.join(box, "plot.jpg"), "wb").write(b"jpg")
        open(os.path.join(home, ".env"), "w").write("API_SERVER_KEY=secret\n")

        self.assertEqual(self.media(b["id"], "/root/.hermes/cache/images/chart.png"), (200, png))
        self.assertEqual(self.media(b["id"], "/workspace/plot.jpg"), (200, b"jpg"))
        self.assertEqual(self.media(b["id"], os.path.join(home, "cache/images/chart.png"))[0], 200)   # a host path in the profile
        self.assertEqual(self.media(b["id"], "/root/.hermes/.env")[0], 415)                          # not a picture
        self.assertEqual(self.media(b["id"], "/root/.hermes/../../../etc/passwd.png")[0], 404)       # outside the profile
        self.assertEqual(self.media(b["id"], "/tmp/x.png")[0], 404)                                  # no sandbox has it
        self.assertEqual(self.media(b["id"], "/root/.hermes/cache/images/chart.png", key="wrong")[0], 401)
        self.assertEqual(self.media("b0000000000", "/root/.hermes/cache/images/chart.png")[0], 404)

    def test_media_of_the_app_profile_maps_its_sandbox_but_no_link_out(self):
        home = os.path.join(self.cfg.profiles, "labbot")
        os.makedirs(os.path.join(home, "images"))
        open(os.path.join(home, "images", "a.webp"), "wb").write(b"webp")
        secret = os.path.join(self.tmp, "secret.png")
        open(secret, "wb").write(b"no")
        os.symlink(secret, os.path.join(home, "link.png"))
        self.assertEqual(self.media("labbot", "/root/.hermes/images/a.webp"), (200, b"webp"))
        self.assertEqual(self.media("labbot", os.path.join(home, "images", "a.webp")), (200, b"webp"))
        self.assertEqual(self.media("labbot", os.path.join(home, "link.png"))[0], 404)
        os.makedirs(os.path.join(self.cfg.profiles, "bluefish"))
        self.assertEqual(self.media("bluefish", "/root/.hermes/images/a.webp")[0], 404)   # not a profile the app may read

    def test_an_answer_to_an_approval_goes_to_hermes_with_the_bots_key(self):
        b = self.make("A")
        s, body = self.call("POST", f"/m/v1/bots/{b['id']}/runs/chatcmpl-abc123/approval", {"choice": "session", "request_id": "r-1"})
        self.assertEqual((s, json.loads(body)), (200, {"ok": True, "resolved": 1}))
        path, auth, sent = Upstream.seen[-1]
        self.assertEqual(path, f"/p/{b['id']}/v1/runs/chatcmpl-abc123/approval")
        self.assertEqual(auth, "Bearer " + bm.read_env_value(os.path.join(self.cfg.profiles, b["id"], ".env"), "API_SERVER_KEY"))
        self.assertEqual(sent, {"choice": "session", "request_id": "r-1"})
        self.assertEqual(self.call("POST", f"/m/v1/bots/{b['id']}/runs/x/approval", {"choice": "yes"})[0], 400)
        self.assertEqual(self.call("POST", f"/m/v1/bots/{b['id']}/runs/x/approval", {"choice": "once"}, key="wrong")[0], 401)

    def test_the_app_profile_gets_files_and_approvals_but_nothing_else(self):
        home = os.path.join(self.cfg.profiles, "labbot")
        os.makedirs(home)
        open(os.path.join(home, ".env"), "w").write("API_SERVER_KEY=lab-key\n")
        s, body = self.call("POST", "/m/v1/bots/labbot/runs/chatcmpl-1/approval", {"choice": "deny"})
        self.assertEqual(s, 200)
        self.assertEqual(Upstream.seen[-1][:2], ("/p/labbot/v1/runs/chatcmpl-1/approval", "Bearer lab-key"))
        req = urllib.request.Request(self.base + "/m/v1/bots/labbot/files", method="POST", data=b"hello",
                                     headers={"Authorization": f"Bearer {KEY}", "X-Filename": "a.txt"})
        with urllib.request.urlopen(req, timeout=10) as r:
            # the admin bot works in this container, so it is told the real path
            self.assertEqual(json.loads(r.read())["path"], os.path.join(home, "attachments", "a.txt"))
        self.assertEqual(open(os.path.join(home, "attachments", "a.txt"), "rb").read(), b"hello")
        for method, path in (("PATCH", "/m/v1/bots/labbot"), ("DELETE", "/m/v1/bots/labbot"), ("POST", "/m/v1/bots/labbot/restore"), ("POST", "/m/v1/bots/bluefish/files")):
            self.assertEqual(self.call(method, path, {"name": "x", "messages": [{"role": "user", "content": "hi"}]})[0], 404, path)

    def test_a_picture_only_the_sandbox_has_is_kept_after_the_reply(self):
        b = self.make("A")
        self.fb.sandboxes[b["id"]] = True
        self.fb.container_files = {"/tmp/chart.png": b"png-bytes"}
        Upstream.reply = "here it is MEDIA:/tmp/chart.png."
        self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions", {"messages": [{"role": "user", "content": "draw"}]})
        kept = self.mgr._rescued(b["id"], "/tmp/chart.png")
        for _ in range(50):
            if os.path.exists(kept):
                break
            time.sleep(0.05)
        # the sandbox is gone now, the copy still answers
        self.fb.sandboxes[b["id"]] = False
        self.fb.container_files = {}
        self.assertEqual(self.media(b["id"], "/tmp/chart.png"), (200, b"png-bytes"))

    def test_a_picture_is_taken_from_a_running_sandbox_on_request(self):
        b = self.make("A")
        self.fb.sandboxes[b["id"]] = True
        self.fb.container_files = {"/tmp/x.jpg": b"jpg"}
        self.assertEqual(self.media(b["id"], "/tmp/x.jpg"), (200, b"jpg"))
        self.assertEqual(self.media(b["id"], "/tmp/missing.jpg")[0], 404)

    def test_the_stream_carries_each_commands_text_and_output(self):
        b = self.make("A")
        notes = os.path.join(self.cfg.profiles, b["id"], "lablink-tools")
        os.makedirs(notes)
        json.dump({"command": "ls -la /workspace", "at": 1}, open(os.path.join(notes, "call_1.start.json"), "w"))
        json.dump({"output": "total 0", "exitCode": 0, "durationMs": 42}, open(os.path.join(notes, "call_1.done.json"), "w"))
        Upstream.events = [
            'data: {"choices":[{"delta":{"content":"Looking"}}]}\n\n',
            'event: hermes.tool.progress\ndata: {"tool":"terminal","label":"ls","toolCallId":"call_1","status":"running"}\n\n',
            ': keepalive\n\n',
            'event: hermes.tool.progress\ndata: {"tool":"terminal","toolCallId":"call_1","status":"completed"}\n\n',
            'event: hermes.tool.progress\ndata: {"tool":"web_search","toolCallId":"call_2","status":"running"}\n\n',
            'data: [DONE]\n\n',
        ]
        s, raw = self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions", {"stream": True, "messages": [{"role": "user", "content": "hi"}]})
        text = raw.decode()
        tools = [json.loads(l[5:]) for l in text.split("\n") if l.startswith("data:") and "lablink" not in l and '"toolCallId"' in l and '"command"' in l]
        self.assertEqual(s, 200)
        self.assertIn('"Looking"', text)
        self.assertEqual(text.count("event: lablink.tool"), 2)   # the web search gets no command window
        self.assertEqual(tools[0], {"toolCallId": "call_1", "status": "running", "command": "ls -la /workspace"})
        self.assertEqual(tools[1], {"toolCallId": "call_1", "status": "completed", "command": "ls -la /workspace", "output": "total 0", "exitCode": 0, "error": None, "durationMs": 42})
        self.assertEqual(os.listdir(notes), [])   # notes are taken once

    def test_the_app_profile_chats_through_the_manager_with_its_own_prompt(self):
        home = os.path.join(self.cfg.profiles, "labbot")
        os.makedirs(home)
        open(os.path.join(home, ".env"), "w").write("API_SERVER_KEY=lab-key\n")
        s, _ = self.call("POST", "/m/v1/bots/labbot/chat/completions",
                         {"messages": [{"role": "system", "content": "be kind"}, {"role": "user", "content": "hi"}]})
        self.assertEqual(s, 200)
        path, auth, sent = Upstream.seen[-1]
        self.assertEqual((path, auth), ("/p/labbot/v1/chat/completions", "Bearer lab-key"))
        self.assertEqual(sent["messages"], [{"role": "system", "content": "be kind"}, {"role": "user", "content": "hi"}])

    def test_fast_mode_only_for_models_that_have_one(self):
        models = json.loads(self.call("GET", "/m/v1/models")[1])["providers"]
        fast = {(p["id"], m["id"]): m["fast"] for p in models for m in p["models"]}
        self.assertEqual(fast, {("deepseek", "deepseek-flash"): False, ("openai-codex", "gpt-5.6-sol"): True})
        b = self.make("A")
        ask = {"messages": [{"role": "user", "content": "hi"}], "modelProvider": "openai-codex", "modelId": "gpt-5.6-sol", "reasoning": "xhigh", "fast": True}
        self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions", ask)
        self.assertEqual(Upstream.seen[-1][2]["model_options"], {"reasoning": {"enabled": True, "effort": "xhigh"}, "fast": True})
        self.assertNotIn("fast", Upstream.seen[-1][2])
        # a model without a fast tier just runs normally
        self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions", {**ask, "modelProvider": "deepseek", "modelId": "deepseek-flash", "reasoning": "high"})
        self.assertNotIn("fast", Upstream.seen[-1][2]["model_options"])

    def test_chat_asks_for_markdown_only_when_the_app_does(self):
        b = self.make("A")
        self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions", {"messages": [{"role": "user", "content": "hi"}], "markdown": True})
        self.assertEqual(Upstream.seen[-1][2]["messages"], [{"role": "system", "content": bm.MARKDOWN_HINT}, {"role": "user", "content": "hi"}])
        self.assertNotIn("markdown", Upstream.seen[-1][2])
        self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions", {"messages": [{"role": "user", "content": "hi"}], "markdown": "yes"})
        self.assertEqual(Upstream.seen[-1][2]["messages"], [{"role": "user", "content": "hi"}])

    def test_chat_streams_and_rejects_trashed(self):
        b = self.make("A")
        s, body = self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions",
                            {"messages": [{"role": "user", "content": "hi"}], "stream": True})
        self.assertEqual(s, 200)
        self.assertEqual(body.decode(), "data: one\n\ndata: two\n\ndata: [DONE]\n\n")
        self.call("DELETE", f"/m/v1/bots/{b['id']}")
        self.assertEqual(self.call("POST", f"/m/v1/bots/{b['id']}/chat/completions",
                                   {"messages": [{"role": "user", "content": "hi"}]})[0], 404)


class Models(Base):
    def test_catalog_is_served(self):
        s, body = self.call("GET", "/m/v1/models")
        self.assertEqual(s, 200)
        self.assertEqual([p["id"] for p in json.loads(body)["providers"]], ["deepseek", "openai-codex"])
        self.assertEqual(self.call("GET", "/m/v1/models", key="wrong")[0], 401)

    def test_a_bot_can_have_a_default_model_and_level(self):
        b = self.make("A", modelProvider="openai-codex", modelId="gpt-5.6-sol", reasoning="high")
        got = json.loads(self.call("GET", "/m/v1/bots")[1])["bots"][0]
        self.assertEqual((got["modelProvider"], got["modelId"], got["reasoning"]), ("openai-codex", "gpt-5.6-sol", "high"))
        s, _ = self.call("PATCH", f"/m/v1/bots/{b['id']}", {"modelProvider": "deepseek", "modelId": "deepseek-flash", "reasoning": "none"})
        self.assertEqual(s, 200)
        s, _ = self.call("PATCH", f"/m/v1/bots/{b['id']}", {"modelProvider": "", "modelId": "", "reasoning": ""})
        self.assertEqual(s, 200)   # back to the default

    def test_unknown_models_and_levels_are_refused(self):
        self.assertEqual(self.call("POST", "/m/v1/bots", {"name": "x", "modelProvider": "deepseek", "modelId": "nope"})[0], 400)
        self.assertEqual(self.call("POST", "/m/v1/bots", {"name": "x", "modelProvider": "deepseek", "modelId": "deepseek-flash", "reasoning": "xhigh"})[0], 400)
        self.assertEqual(self.call("POST", "/m/v1/bots", {"name": "x", "reasoning": "high"})[0], 400)
        self.assertEqual(json.loads(self.call("GET", "/m/v1/bots")[1])["bots"], [])

    def chat(self, bid, **extra):
        return self.call("POST", f"/m/v1/bots/{bid}/chat/completions", {"messages": [{"role": "user", "content": "hi"}], **extra})

    def test_chat_sends_the_requested_model_and_level_upstream(self):
        b = self.make("A")
        self.assertEqual(self.chat(b["id"], modelProvider="openai-codex", modelId="gpt-5.6-sol", reasoning="xhigh")[0], 200)
        sent = Upstream.seen[-1][2]
        self.assertEqual((sent["provider"], sent["model"]), ("openai-codex", "gpt-5.6-sol"))
        self.assertEqual(sent["model_options"], {"reasoning": {"enabled": True, "effort": "xhigh"}})
        self.chat(b["id"], modelProvider="deepseek", modelId="deepseek-flash", reasoning="none")
        self.assertEqual(Upstream.seen[-1][2]["model_options"], {"reasoning": {"enabled": False}})

    def test_chat_uses_the_bots_default_and_otherwise_the_global_model(self):
        plain = self.make("plain")
        self.chat(plain["id"])
        sent = Upstream.seen[-1][2]
        self.assertEqual(sent["model"], "hermes-agent")
        self.assertNotIn("provider", sent)
        self.assertNotIn("model_options", sent)
        withdef = self.make("def", modelProvider="deepseek", modelId="deepseek-flash", reasoning="high")
        self.chat(withdef["id"])
        sent = Upstream.seen[-1][2]
        self.assertEqual((sent["provider"], sent["model"]), ("deepseek", "deepseek-flash"))
        self.assertEqual(sent["model_options"]["reasoning"]["effort"], "high")

    def test_chat_refuses_models_that_are_not_offered(self):
        b = self.make("A")
        before = len(Upstream.seen)
        self.assertEqual(self.chat(b["id"], modelProvider="openai-codex", modelId="gpt-5.5")[0], 400)
        self.assertEqual(self.chat(b["id"], modelProvider="deepseek", modelId="deepseek-flash", reasoning="xhigh")[0], 400)
        self.assertEqual(len(Upstream.seen), before)


class Files(Base):
    def upload(self, bid, name, data, key=KEY):
        from urllib.parse import quote
        req = urllib.request.Request(self.base + f"/m/v1/bots/{bid}/files", method="POST", data=data,
                                     headers={"Authorization": f"Bearer {key}", "X-Filename": quote(name), "Content-Type": "application/octet-stream"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def folder(self, bid):
        return os.path.join(self.cfg.profiles, bid, "attachments")

    def test_a_file_lands_in_the_bots_attachments_and_the_sandbox_path_is_returned(self):
        b = self.make("A")
        s, r = self.upload(b["id"], "报告 v2.pdf", b"%PDF-data")
        self.assertEqual(s, 201)
        self.assertEqual((r["name"], r["size"], r["path"]), ("报告 v2.pdf", 9, "/root/.hermes/attachments/报告 v2.pdf"))
        self.assertEqual(open(os.path.join(self.folder(b["id"]), "报告 v2.pdf"), "rb").read(), b"%PDF-data")
        self.assertEqual([n for n in os.listdir(self.folder(b["id"])) if n.startswith(".upload")], [])

    def test_names_cannot_escape_and_duplicates_get_a_suffix(self):
        b = self.make("A")
        for evil in ("../../etc/passwd", "..\\..\\x.txt", "/abs/path/x.txt", ".hidden", "a\x00b.txt"):
            s, r = self.upload(b["id"], evil, b"x")
            self.assertEqual(s, 201, evil)
            self.assertNotIn("..", r["name"])
            self.assertFalse(r["name"].startswith("."))
            self.assertTrue(os.path.realpath(os.path.join(self.folder(b["id"]), r["name"])).startswith(os.path.realpath(self.folder(b["id"]))))
        self.assertEqual(self.upload(b["id"], "n.txt", b"1")[1]["name"], "n.txt")
        self.assertEqual(self.upload(b["id"], "n.txt", b"2")[1]["name"], "n-2.txt")
        self.assertEqual(self.upload(b["id"], "n.txt", b"3")[1]["name"], "n-3.txt")

    def test_limits_and_unknown_bots(self):
        b = self.make("A")
        self.assertEqual(self.upload(b["id"], "x", b"")[0], 400)
        self.cfg.bot_storage = 10
        self.assertEqual(self.upload(b["id"], "a", b"12345678")[0], 201)
        s, r = self.upload(b["id"], "b", b"12345678")
        self.assertEqual((s, r["error"]["code"]), (413, "bot_storage_full"))
        self.assertEqual(self.upload("b0000000000", "x", b"1")[0], 404)
        self.assertEqual(self.upload(b["id"], "x", b"1", key="wrong")[0], 401)
        self.call("DELETE", f"/m/v1/bots/{b['id']}")
        self.assertEqual(self.upload(b["id"], "x", b"1")[0], 404)

    def test_an_upload_over_the_cap_is_refused_before_it_is_read(self):
        b = self.make("A")
        old = bm.MAX_UPLOAD
        bm.MAX_UPLOAD = 4
        try:
            s, r = self.upload(b["id"], "big", b"12345")
        finally:
            bm.MAX_UPLOAD = old
        self.assertEqual((s, r["error"]["code"]), (413, "too_large"))
        self.assertFalse(os.path.isdir(self.folder(b["id"])) and os.listdir(self.folder(b["id"])))


class Images(Base):
    URL = "data:image/jpeg;base64,/9j/4AAQ"

    def chat(self, bid, content):
        return self.call("POST", f"/m/v1/bots/{bid}/chat/completions", {"messages": [{"role": "user", "content": content}]})

    def test_text_and_inline_images_are_forwarded(self):
        b = self.make("A")
        parts = [{"type": "text", "text": "what is this?"}, {"type": "image_url", "image_url": {"url": self.URL}}]
        self.assertEqual(self.chat(b["id"], parts)[0], 200)
        self.assertEqual(Upstream.seen[-1][2]["messages"][0]["content"], parts)

    def test_remote_urls_and_odd_parts_are_refused(self):
        b = self.make("A")
        before = len(Upstream.seen)
        for bad in ([{"type": "image_url", "image_url": {"url": "http://10.0.0.10/secret.png"}}],
                    [{"type": "image_url", "image_url": {"url": "file:///etc/passwd"}}],
                    [{"type": "image_url", "image_url": {"url": "data:text/html;base64,AAAA"}}],
                    [{"type": "video", "url": "x"}], [], 5):
            self.assertEqual(self.chat(b["id"], bad)[0], 400, bad)
        self.assertEqual(len(Upstream.seen), before)

    def test_too_many_images(self):
        b = self.make("A")
        parts = [{"type": "image_url", "image_url": {"url": self.URL}}] * (bm.MAX_IMAGES + 1)
        s, body = self.chat(b["id"], parts)
        self.assertEqual((s, json.loads(body)["error"]["code"]), (400, "too_many_images"))


class Janitor(Base):
    def test_unknown_profile_is_quarantined_not_deleted(self):
        os.makedirs(os.path.join(self.cfg.profiles, "b0123456789"))
        open(os.path.join(self.cfg.profiles, "b0123456789", "state.db"), "w").write("data")
        self.mgr.janitor()
        self.assertTrue(os.path.exists(os.path.join(self.cfg.trash, "b0123456789", "state.db")))
        self.assertEqual(len(json.loads(self.call("GET", "/m/v1/bots")[1])["trash"]), 1)

    def test_other_profiles_are_left_alone(self):
        os.makedirs(os.path.join(self.cfg.profiles, "labbot"))
        self.mgr.janitor()
        self.assertTrue(os.path.isdir(os.path.join(self.cfg.profiles, "labbot")))

    def test_entry_without_profile_is_dropped(self):
        b = self.make("A")
        shutil.rmtree(os.path.join(self.cfg.profiles, b["id"]))
        self.mgr.janitor()
        self.assertEqual(json.loads(self.call("GET", "/m/v1/bots")[1])["bots"], [])

    def test_orphan_sandbox_is_removed(self):
        self.fb.sandboxes["b0123456789"] = True
        b = self.make("A")
        self.fb.sandboxes[b["id"]] = True
        self.mgr.janitor()
        self.assertNotIn("b0123456789", self.fb.sandboxes)
        self.assertIn(b["id"], self.fb.sandboxes)

    def test_stale_creation_is_discarded(self):
        self.mgr.reg.bots["b1111111111"] = {"id": "b1111111111", "state": "creating", "name": "x", "role": "",
                                            "systemPrompt": "", "color": 1, "createdAt": int(time.time()) - 3600}
        self.mgr.janitor()
        self.assertNotIn("b1111111111", self.mgr.reg.bots)

    def test_trash_expires_after_the_retention_period(self):
        old, fresh = self.make("old"), self.make("fresh")
        self.call("DELETE", f"/m/v1/bots/{old['id']}")
        self.call("DELETE", f"/m/v1/bots/{fresh['id']}")
        self.mgr.reg.bots[old["id"]]["trashedAt"] = int(time.time()) - 8 * 86400
        self.mgr.reg.bots[fresh["id"]]["trashedAt"] = int(time.time()) - 6 * 86400
        self.mgr.expire_trash()
        ids = [b["id"] for b in json.loads(self.call("GET", "/m/v1/bots")[1])["trash"]]
        self.assertEqual(ids, [fresh["id"]])
        self.assertFalse(os.path.exists(os.path.join(self.cfg.trash, old["id"])))
        listing = json.loads(self.call("GET", "/m/v1/bots")[1])
        self.assertEqual(listing["trash"][0]["expiresAt"], self.mgr.reg.bots[fresh["id"]]["trashedAt"] + 7 * 86400)
        self.assertEqual((listing["trashLimit"], listing["trashDays"]), (2, 7))

    def test_registry_survives_a_restart(self):
        b = self.make("A")
        again = bm.Manager(self.cfg, self.fb)
        self.assertIn(b["id"], again.reg.bots)


class ShellTest(Base):
    """The terminal: a WebSocket into the bot's own machine."""

    def ws(self, path, key=KEY):
        import base64, socket
        port = self.srv.server_address[1]
        s = socket.create_connection(("127.0.0.1", port), timeout=10)
        nonce = base64.b64encode(os.urandom(16)).decode()
        s.sendall((f"GET {path} HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {nonce}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {key}\r\n\r\n").encode())
        f = s.makefile("rb")
        status = f.readline().decode()
        while f.readline() not in (b"\r\n", b""):
            pass
        return s, f, status

    @staticmethod
    def masked(payload, opcode=2):
        mask = os.urandom(4)
        n = len(payload)
        head = bytes([0x80 | opcode]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, "big"))
        return head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

    def test_frames_round_trip(self):
        import io
        for n in (0, 5, 200, 70000):
            data = os.urandom(n)
            self.assertEqual(bm.ws_read(io.BytesIO(bm.ws_frame(data))), (2, data))
            self.assertEqual(bm.ws_read(io.BytesIO(self.masked(data, 1) if n < 65536 else bm.ws_frame(data, 1))), (1, data))
        self.assertEqual(bm.ws_accept("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")

    def test_shell_runs_in_the_profile_and_takes_typing_and_resizes(self):
        home = os.path.join(self.cfg.profiles, "labbot")
        os.makedirs(home)
        self.mgr.shell_argv = lambda bid: (["/bin/sh"], self.mgr.app_bot_home(bid))
        s, f, status = self.ws("/m/v1/bots/labbot/shell?cols=100&rows=30")
        self.assertIn("101", status)
        s.sendall(self.masked(b'{"resize": [120, 40]}', 1))
        s.sendall(self.masked(b"pwd; stty size; echo done-$((1+1))\n"))
        seen = b""
        deadline = time.time() + 10
        while b"done-2" not in seen and time.time() < deadline:
            frame = bm.ws_read(f)
            if frame is None:
                break
            if frame[0] == 2:
                seen += frame[1]
        self.assertIn(b"done-2", seen)
        self.assertIn(os.path.realpath(home).encode(), seen.replace(b"\r", b""))
        self.assertIn(b"40 120", seen)
        s.sendall(self.masked(b"", 8))
        s.close()

    def test_shell_needs_the_key_a_websocket_and_a_sandbox(self):
        b = self.make("A", sandboxed=True)
        s, f, status = self.ws(f"/m/v1/bots/{b['id']}/shell", key="wrong" * 6)
        self.assertIn("401", status); s.close()
        self.assertEqual(self.call("GET", f"/m/v1/bots/{b['id']}/shell")[0], 400)
        self.fb.sandboxes.pop(b["id"], None)
        s, f, status = self.ws(f"/m/v1/bots/{b['id']}/shell")
        self.assertIn("409", status); s.close()
        argv, cwd = (self.fb.sandboxes.__setitem__(b["id"], True), self.mgr.shell_argv(b["id"]))[1]
        self.assertEqual(argv[:3], ["docker", "exec", "-it"])
        self.assertIn("cid-" + b["id"], argv)
        self.assertIsNone(cwd)
        self.assertEqual(self.ws("/m/v1/bots/bluefish/shell")[2].split()[1], "404")


class SandboxChoice(Base):
    """A bot gets a sandbox (commands, files, a terminal) only when it is made with one."""

    def test_bots_are_made_without_a_sandbox_unless_asked(self):
        plain = self.make("Writer")
        coder = self.make("Coder", sandboxed=True)
        self.assertFalse(plain["sandboxed"])
        self.assertTrue(coder["sandboxed"])
        self.assertIn(("chat_only", plain["id"]), self.fb.calls)
        self.assertNotIn(("chat_only", coder["id"]), self.fb.calls)
        listed = {b["id"]: b["sandboxed"] for b in json.loads(self.call("GET", "/m/v1/bots")[1])["bots"]}
        self.assertEqual(listed, {plain["id"]: False, coder["id"]: True})
        # it is chosen once, when the bot is made
        s, b = self.call("PATCH", f"/m/v1/bots/{plain['id']}", {"name": "Writer 2", "sandboxed": True})
        self.assertEqual(s, 200)
        self.assertFalse(json.loads(b)["sandboxed"])
        self.assertEqual(self.call("POST", "/m/v1/bots", {"name": "X", "sandboxed": "yes"})[0], 400)

    def test_older_bots_keep_their_sandbox(self):
        b = self.make("Old", sandboxed=True)
        with self.mgr.reg.lock:
            self.mgr.reg.bots[b["id"]].pop("sandboxed")
        self.assertTrue(json.loads(self.call("GET", "/m/v1/bots")[1])["bots"][0]["sandboxed"])
        self.fb.sandboxes[b["id"]] = True
        self.assertEqual(self.mgr.shell_argv(b["id"])[0][:2], ["docker", "exec"])

    def test_terminal_only_for_sandbox_bots_and_the_admin_bot(self):
        plain = self.make("Writer")
        with self.assertRaises(bm.ApiError) as e:
            self.mgr.shell_argv(plain["id"])
        self.assertEqual(e.exception.status, 403)
        os.makedirs(os.path.join(self.cfg.profiles, "labbot"))
        argv, cwd = self.mgr.shell_argv("labbot")
        self.assertEqual((argv[0], argv[-1], cwd), ("ssh", bm.ADMIN_SSH_HOST, None))


class ChatSync(Base):
    """Chats shared between the apps: stored per bot, the newest edit wins, deletions travel as tombstones."""

    def conv(self, cid, updated, title="t"):
        return {"conversation": {"id": cid, "title": title, "created": 1, "updated": updated, "pinned": False, "renamed": False},
                "messages": [{"id": "m1", "role": "user", "content": "hi", "time": 1}]}

    def test_store_list_get_and_the_newest_edit_wins(self):
        os.makedirs(os.path.join(self.cfg.profiles, "labbot"))
        s, b = self.call("PUT", "/m/v1/chats/labbot/C-1", self.conv("C-1", 100))
        self.assertEqual((s, json.loads(b)["stored"]), (200, True))
        # an older copy from another device does not overwrite it; the newer one comes back instead
        s, b = self.call("PUT", "/m/v1/chats/labbot/C-1", self.conv("C-1", 50, "old"))
        self.assertEqual((json.loads(b)["stored"], json.loads(b)["conversation"]["title"]), (False, "t"))
        self.call("PUT", "/m/v1/chats/labbot/C-1", self.conv("C-1", 200, "newer"))
        listed = json.loads(self.call("GET", "/m/v1/chats/labbot")[1])["conversations"]
        self.assertEqual([(c["id"], c["title"], c["updated"]) for c in listed], [("C-1", "newer", 200)])
        got = json.loads(self.call("GET", "/m/v1/chats/labbot/C-1")[1])
        self.assertEqual(got["messages"][0]["content"], "hi")

    def test_a_deletion_reaches_the_other_devices(self):
        os.makedirs(os.path.join(self.cfg.profiles, "labbot"))
        self.call("PUT", "/m/v1/chats/labbot/c2", self.conv("c2", 100))
        self.assertEqual(self.call("DELETE", "/m/v1/chats/labbot/c2")[0], 200)
        listed = json.loads(self.call("GET", "/m/v1/chats/labbot")[1])["conversations"]
        self.assertTrue(listed[0]["deleted"])
        # a device that still had it, with an older edit, cannot bring it back
        self.assertFalse(json.loads(self.call("PUT", "/m/v1/chats/labbot/c2", self.conv("c2", 150))[1])["stored"])

    def test_only_known_bots_with_the_key_and_sane_input(self):
        b = self.make("A")
        self.assertEqual(self.call("PUT", f"/m/v1/chats/{b['id']}/x", self.conv("x", 1))[0], 200)
        self.assertEqual(self.call("GET", "/m/v1/chats/bluefish")[0], 404)
        self.assertEqual(self.call("GET", f"/m/v1/chats/{b['id']}", key="wrong" * 6)[0], 401)
        bad = self.conv("y", 1); bad["conversation"]["updated"] = "soon"
        self.assertEqual(self.call("PUT", f"/m/v1/chats/{b['id']}/y", bad)[0], 400)
        self.assertEqual(self.call("PUT", f"/m/v1/chats/{b['id']}/a.b", self.conv("a", 1))[0], 404)
        # a bot removed for good takes its shared chats with it
        self.call("DELETE", f"/m/v1/bots/{b['id']}")
        self.call("DELETE", f"/m/v1/bots/{b['id']}?purge=1")
        self.assertFalse(os.path.exists(self.mgr.chat_dir(b["id"])))


if __name__ == "__main__":
    unittest.main()
