import unittest

import apply_policy as ap

LAB = """model:
  default: x
# --- terminal ---
terminal:
  backend: "local"
  timeout: 180
  container_memory: 5120
browser:
  inactivity_timeout: 120
platform_toolsets:
  cli: [terminal]
  api_server:
    - memory
    - web

stt:
  enabled: false
"""
TPL = """terminal:
  backend: "docker"
  timeout: 180
  container_memory: 1024
  docker_image: python:3.12-slim
browser:
  x: 1
"""


class ApplyPolicy(unittest.TestCase):
    def test_the_hook_is_added_once(self):
        once = ap.add_hook(LAB)
        self.assertIn('command: "python3 /opt/bot-manager/policy_hook.py"', once)
        self.assertIn("hooks_auto_accept: true", once)
        self.assertIsNone(ap.add_hook(once))
        with self.assertRaises(ValueError):
            ap.add_hook(LAB + "hooks:\n  pre_llm_call: []\n")

    def test_the_older_block_is_upgraded_in_place(self):
        old = LAB + """
# LabLink policy: downloads, installs/updates and deletions ask the user in the app first (deploy/bot-manager/policy_hook.py)
hooks:
  pre_tool_call:
    - matcher: "^terminal$"
      command: "python3 /opt/bot-manager/policy_hook.py"
      timeout: 20
      fail_closed: true
hooks_auto_accept: true
"""
        new = ap.add_hook(old)
        self.assertIn("post_tool_call:", new)
        self.assertEqual(new.count("hooks:"), 1)
        self.assertIsNone(ap.add_hook(new))

    def test_the_default_bot_moves_into_the_sandbox_with_files_and_commands(self):
        out = ap.set_api_toolsets(ap.use_template_terminal(LAB, TPL), ap.APP_TOOLSETS)
        self.assertIn('terminal:\n  backend: "docker"\n  timeout: 180\n  container_memory: 1024\n  docker_image: python:3.12-slim\nbrowser:\n  inactivity_timeout: 120', out)
        self.assertIn("  api_server:\n    - file\n    - memory\n    - session_search\n    - terminal\n    - todo\n    - vision\n    - web\n\nstt:", out)
        self.assertTrue(out.startswith("model:\n  default: x\n"))
        self.assertEqual(out, ap.set_api_toolsets(ap.use_template_terminal(out, TPL), ap.APP_TOOLSETS))



    def test_the_admin_bot_runs_its_commands_here(self):
        out = ap.set_api_toolsets(ap.use_local_terminal(LAB, "/h/profiles/labbot/workspace"), ap.APP_TOOLSETS)
        self.assertIn('terminal:\n  backend: "local"\n  cwd: "/h/profiles/labbot/workspace"\n  timeout: 300\n  lifetime_seconds: 300\nbrowser:', out)
        self.assertNotIn("container_memory", out)
        self.assertIn("    - terminal\n", out)
        self.assertIn("model:\n  default: x", out)

if __name__ == "__main__":
    unittest.main()
