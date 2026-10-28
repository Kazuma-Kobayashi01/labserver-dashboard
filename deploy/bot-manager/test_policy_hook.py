import json
import os
import subprocess
import sys
import tempfile
import unittest

import policy_hook as ph


def ask(command, home=""):
    return ph.decide({"tool_name": "terminal", "tool_input": {"command": command}}, home)


class Policy(unittest.TestCase):
    def test_safe_work_runs_without_asking(self):
        for c in ["ls -la /workspace", "python3 plot.py", "cat /root/.hermes/attachments/report.pdf | head",
                  "python3 -c 'import matplotlib; print(1)'", "echo hi > /workspace/out.txt", "mkdir -p /workspace/a",
                  "cp /root/.hermes/attachments/a.csv /workspace/", "grep -r form /workspace", "python3 -m http.server --help",
                  "sed -i s/a/b/ /workspace/x.md", "git status", "git log -1", "pip list", "npm run build", "firmware"]:
            self.assertIsNone(ask(c), c)

    def test_downloads_installs_and_deletes_ask(self):
        cases = {
            "curl -L https://x.y/a.zip -o a.zip": "download", "wget https://x.y": "download",
            "git clone https://github.com/a/b": "download", "cd b && git pull": "download",
            "python3 -c \"import urllib.request; urllib.request.urlretrieve('http://x', 'y')\"": "download",
            "pip install numpy": "install", "python3 -m pip install -U pip": "install", "npm i left-pad": "install",
            "apt-get -y install ffmpeg": "install", "apt update": "install", "npx create-react-app x": "install",
            "rm -rf /workspace/data": "delete", "rm a.txt": "delete", "find . -name '*.log' -delete": "delete",
            "git clean -fdx": "delete", "python3 -c 'import shutil; shutil.rmtree(\"x\")'": "delete",
        }
        for c, kind in cases.items():
            r = ask(c)
            self.assertIsNotNone(r, c)
            self.assertEqual((r["action"], r["rule_key"]), ("approve", f"lablink-{kind}"), c)
            self.assertIn(c[:20], r["message"])

    def test_a_script_is_judged_by_what_it_does(self):
        home = tempfile.mkdtemp()
        ws = os.path.join(home, "sandboxes", "docker", "profile_x", "workspace")
        os.makedirs(ws)
        open(os.path.join(ws, "fetch.sh"), "w").write("#!/bin/sh\ncurl -O https://x.y/big.bin\n")
        open(os.path.join(ws, "draw.py"), "w").write("import matplotlib\nprint('ok')\n")
        self.assertEqual(ask("bash /workspace/fetch.sh", home)["rule_key"], "lablink-download")
        self.assertEqual(ask("cd /workspace && sh fetch.sh", home)["rule_key"], "lablink-download")
        self.assertIsNone(ask("python3 /workspace/draw.py", home))
        # a link out of the profile is not followed
        os.symlink("/etc/passwd", os.path.join(ws, "evil.sh"))
        self.assertIsNone(ask("bash /workspace/evil.sh", home))

    def test_other_tools_are_left_alone(self):
        self.assertIsNone(ph.decide({"tool_name": "write_file", "tool_input": {"path": "/workspace/rm.txt", "content": "rm -rf /"}}, ""))

    def test_the_script_speaks_the_hook_protocol(self):
        here = os.path.dirname(os.path.abspath(__file__))
        run = lambda payload: subprocess.run([sys.executable, os.path.join(here, "policy_hook.py")], input=json.dumps(payload),
                                             capture_output=True, text=True, timeout=20).stdout
        self.assertEqual(run({"tool_name": "terminal", "tool_input": {"command": "ls"}}), "")
        self.assertEqual(json.loads(run({"tool_name": "terminal", "tool_input": {"command": "rm x"}}))["rule_key"], "lablink-delete")


if __name__ == "__main__":
    unittest.main()
