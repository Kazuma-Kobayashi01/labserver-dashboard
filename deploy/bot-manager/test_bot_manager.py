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

    def gateway_serves(self, profile, key):
        return self.serves

    def _host_port(self):
        return "127.0.0.1", self.upstream_port


class Upstream(BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Upstream.seen.append((self.path, self.headers.get("Authorization"), body))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream" if body.get("stream") else "application/json")
        self.end_headers()
        if body.get("stream"):
            for part in ("data: one\n\n", "data: two\n\n", "data: [DONE]\n\n"):
                self.wfile.write(part.encode())
                self.wfile.flush()
        else:
            self.wfile.write(json.dumps({"choices": [{"message": {"content": "pong"}}]}).encode())


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


if __name__ == "__main__":
    unittest.main()
