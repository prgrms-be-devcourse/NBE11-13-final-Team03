"""Exercise the actual workflow readiness/rollback shell with local command stubs."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = (r"C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash"))
TEXT = (ROOT / ".github/workflows/deploy.yml").read_text(encoding="utf-8")
START = TEXT.index("          # backend keeps its existing HTTP")
END = TEXT.index("          docker image prune -f", START)
CHECK = "\n".join(line[10:] for line in TEXT[START:END].splitlines())


class ReadinessTest(unittest.TestCase):
    def run_check(self, service="data-service", result="0", previous=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "bin"
            binary.mkdir()
            (binary / "docker").write_text(
                '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >>trace.txt\n'
                'if [[ "$*" == *"exec -T data-service java"* ]]; then exit "$PROBE_EXIT"; fi\n', encoding="utf-8")
            (binary / "curl").write_text('#!/usr/bin/env bash\nprintf 401\n', encoding="utf-8")
            for path in binary.iterdir():
                path.chmod(0o755)
            (root / ".env").write_text("BACKEND_PORT=8080\n")
            (root / "deploy.env").write_text("DATA_SERVICE_IMAGE=new\n")
            (root / "deploy.env.prev").write_text("DATA_SERVICE_IMAGE=old\n" if previous else "")
            script = root / "check.sh"
            script.write_text(
                'set -euo pipefail\n'
                'bin="$TEST_BIN"; if command -v cygpath >/dev/null; then bin=$(cygpath -u "$bin"); fi\n'
                'export PATH="$bin:$PATH"\n'
                'compose() { docker compose "$@"; }\n'
                'sleep() { SECONDS=$((SECONDS + 180)); }\n' + CHECK, encoding="utf-8")
            env = dict(os.environ, TEST_BIN=str(binary), SERVICE=service, PROBE_EXIT=result,
                       IMAGE_VAR="DATA_SERVICE_IMAGE", IMAGE="new")
            process = subprocess.run([BASH, str(script)], cwd=root, env=env,
                                     capture_output=True, text=True, timeout=20)
            return (process.returncode, (root / "deploy.env").read_text(),
                    (root / "trace.txt").read_text() if (root / "trace.txt").exists() else "", process.stderr)

    def test_authenticated_read_succeeds_without_rollback(self):
        code, image, trace, error = self.run_check()
        self.assertEqual(code, 0, error)
        self.assertIn("DATA_SERVICE_IMAGE=new", image)
        self.assertIn("CollectionGrpcProbe", trace)
        self.assertNotIn("logs", trace)

    def test_probe_failure_restores_previous_image(self):
        code, image, trace, _ = self.run_check(result="1")
        self.assertEqual(code, 1)
        self.assertIn("DATA_SERVICE_IMAGE=old", image)
        self.assertIn("up -d data-service", trace)

    def test_first_failed_deployment_stops_service(self):
        code, _, trace, _ = self.run_check(result="1", previous=False)
        self.assertEqual(code, 1)
        self.assertIn("stop data-service", trace)

    def test_notification_checks_port_inside_container(self):
        code, _, trace, error = self.run_check(service="notification-service")
        self.assertEqual(code, 0, error)
        self.assertIn("exec -T notification-service bash -c echo > /dev/tcp/127.0.0.1/8081", trace)
        self.assertNotIn("CollectionGrpcProbe", trace)

    def test_backend_keeps_http_reachability_check(self):
        code, _, trace, error = self.run_check(service="backend")
        self.assertEqual(code, 0, error)
        self.assertNotIn("CollectionGrpcProbe", trace)


if __name__ == "__main__":
    unittest.main()
