"""Exercise the actual Deploy workflow image-retention shell with a fake docker."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = (r"C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash"))
TEXT = (ROOT / ".github/workflows/deploy.yml").read_text(encoding="utf-8")
START = TEXT.index("          # Image retention")
END = TEXT.index("          REMOTE", START)
RETENTION = "\n".join(line[10:] for line in TEXT[START:END].splitlines())
REPO = "ghcr.io/org/app/backend"
OTHER = "ghcr.io/org/app/notification-service"

# images.txt: 서버에 있는 이미지 목록. rmi는 목록에서 지우고, busy.txt에 있는 이미지는 사용 중이라 실패한다.
FAKE_DOCKER = r'''#!/usr/bin/env bash
if [ "$1" = images ]; then
  repo="${@: -1}"
  grep "^$repo:" images.txt || true
elif [ "$1" = rmi ]; then
  if grep -qxF "$2" busy.txt 2>/dev/null; then exit 1; fi
  grep -vxF "$2" images.txt > images.tmp || true; mv images.tmp images.txt
  echo "$2" >> removed.txt
fi
'''


class ImageRetentionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        binary = self.root / "bin"
        binary.mkdir()
        (binary / "docker").write_text(FAKE_DOCKER, encoding="utf-8")
        (binary / "docker").chmod(0o755)
        (self.root / "images.txt").write_text("")
        self.script = self.root / "retention.sh"
        self.script.write_text(
            'set -euo pipefail\n'
            'bin="$TEST_BIN"; if command -v cygpath >/dev/null; then bin=$(cygpath -u "$bin"); fi\n'
            'export PATH="$bin:$PATH"\n' + RETENTION, encoding="utf-8")
        self.binary = binary

    def tearDown(self):
        self.directory.cleanup()

    def images(self, *names):
        (self.root / "images.txt").write_text("".join(name + "\n" for name in names))

    def deploy(self, tag, service="backend", repo=REPO):
        image = f"{repo}:{tag}"
        with (self.root / "images.txt").open("a") as stream:
            stream.write(image + "\n")
        env = dict(os.environ, TEST_BIN=str(self.binary), SERVICE=service, IMAGE=image)
        process = subprocess.run([BASH, str(self.script)], cwd=self.root, env=env,
                                 capture_output=True, text=True, timeout=20)
        self.assertEqual(process.returncode, 0, process.stderr + process.stdout)
        return process.stdout

    def history(self, service="backend"):
        return (self.root / "deploy-history" / service).read_text().split()

    def remaining(self):
        return (self.root / "images.txt").read_text().split()

    def test_first_run_keeps_only_the_running_image_of_that_service(self):
        self.images(f"{REPO}:sha-a", f"{REPO}:sha-b", f"{OTHER}:sha-a")
        self.deploy("sha-new")
        self.assertEqual(self.history(), [f"{REPO}:sha-new"])
        self.assertEqual(sorted(self.remaining()), sorted([f"{REPO}:sha-new", f"{OTHER}:sha-a"]))

    def test_keeps_last_three_successful_images_in_order(self):
        for tag in ("sha-1", "sha-2", "sha-3", "sha-4"):
            self.deploy(tag)
        self.assertEqual(self.history(), [f"{REPO}:sha-2", f"{REPO}:sha-3", f"{REPO}:sha-4"])
        self.assertEqual(sorted(self.remaining()), sorted(self.history()))

    def test_redeploying_the_same_image_does_not_duplicate_history(self):
        for tag in ("sha-1", "sha-2", "sha-2", "sha-3"):
            self.deploy(tag)
        self.assertEqual(self.history(), [f"{REPO}:sha-1", f"{REPO}:sha-2", f"{REPO}:sha-3"])

    def test_rollback_to_an_older_image_moves_it_to_the_newest_position(self):
        for tag in ("sha-1", "sha-2", "sha-3", "sha-1"):
            self.deploy(tag)
        self.assertEqual(self.history(), [f"{REPO}:sha-2", f"{REPO}:sha-3", f"{REPO}:sha-1"])

    def test_services_on_the_same_server_keep_separate_histories(self):
        self.deploy("sha-1")
        self.deploy("sha-x", service="notification-service", repo=OTHER)
        self.deploy("sha-2")
        self.assertEqual(self.history("notification-service"), [f"{OTHER}:sha-x"])
        self.assertIn(f"{OTHER}:sha-x", self.remaining())

    def test_image_in_use_is_warned_not_failed(self):
        self.images(f"{REPO}:sha-busy")
        (self.root / "busy.txt").write_text(f"{REPO}:sha-busy\n")
        output = self.deploy("sha-new")
        self.assertIn("::warning::", output)
        self.assertIn(f"{REPO}:sha-busy", self.remaining())


    def test_history_write_failure_warns_and_skips_cleanup(self):
        self.images(f"{REPO}:sha-old")
        (self.root / "deploy-history").write_text("not a directory")
        output = self.deploy("sha-new")
        self.assertIn("::warning::배포 이력 저장 실패", output)
        self.assertIn(f"{REPO}:sha-old", self.remaining())
        self.assertFalse((self.root / "removed.txt").exists())

    def test_summary_reports_images_left_on_the_server(self):
        self.images(f"{REPO}:sha-busy")
        (self.root / "busy.txt").write_text(f"{REPO}:sha-busy\n")
        output = self.deploy("sha-new")
        self.assertIn("이력 1개, 서버 이미지 2개, 0개 삭제", output)

if __name__ == "__main__":
    unittest.main()
