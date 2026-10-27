"""Run on Linux: python3 -m unittest discover -s deploy/tests -p test_db_backup.py."""
import fcntl
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
import hashlib
import subprocess
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("backup", ROOT / "deploy/db/backup/backup.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        example = (ROOT / "deploy/db/backup/backup.ini.example").read_text()
        example = example.replace("REPLACE_WITH_BACKUP_BUCKET", "test-bucket").replace("REPLACE_WITH_DEPLOYED_RELEASE", "test-release")
        example = example.replace("/home/ubuntu/yon-db", str(self.root)).replace("/var/lib/yon-backup", str(self.root / "state"))
        (self.root / "backup.ini").write_text(example)
        self.backup = MODULE.Backup(self.root / "backup.ini")
        self.objects = {}
        self.commands = []
        self.failure = None
        self.primary = "f"
        self.archive_age = "0"
        self.addCleanup(patch.stopall)
        patch.object(self.backup, "run", side_effect=self.run_command).start()

    def run_command(self, command, *, output=None, input_file=None, timeout=None):
        self.commands.append(command)
        text = " ".join(command)
        if self.failure and self.failure in text:
            raise RuntimeError("injected failure")
        if command[0] == "aws":
            if command[1:3] == ["s3", "cp"]:
                self.objects[command[4]] = Path(command[3]).read_bytes()
                return ""
            key = "s3://test-bucket/" + command[command.index("--key") + 1]
            return str(len(self.objects[key]))
        if "pg_is_in_recovery" in text:
            return self.primary
        if "SHOW server_version" in text:
            return "16"
        if "SHOW archive_mode" in text:
            return "on"
        if "pg_ls_dir" in text:
            return self.archive_age
        if "df -P" in text:
            return "30"
        if output:
            output.write(b"roles" if "pg_dumpall" in text else b"PGDMP-test")
        return "[]" if "--output=json" in text else ""

    def test_manifest_is_uploaded_last_and_hashes_match(self):
        self.assertEqual(0, self.backup.execute("daily"))
        self.assertEqual(3, len(self.objects))
        self.assertTrue(list(self.objects)[-1].endswith("manifest.json"))
        manifest = json.loads(list(self.objects.values())[-1])
        self.assertEqual("test-release", manifest["release_reference"])
        self.assertEqual({"database.dump", "globals.sql"}, set(manifest["files"]))
        prefix = list(self.objects)[-1].rsplit("/", 1)[0]
        for name, details in manifest["files"].items():
            self.assertEqual(hashlib.sha256(self.objects[prefix + "/" + name]).hexdigest(), details["sha256"])
        self.assertEqual("OK", self.backup.read_state("attempt")["status"])

    def test_failed_dump_does_not_upload_or_report_success(self):
        self.failure = "pg_dump "
        with self.assertRaises(RuntimeError):
            self.backup.execute("daily")
        self.assertFalse(self.objects)
        self.assertFalse(self.backup.read_state("latest-success"))
        self.assertEqual("FAILED", self.backup.read_state("attempt")["status"])

    def test_partial_upload_has_no_completion_manifest(self):
        self.failure = "/globals.sql --only-show-errors"
        with self.assertRaises(RuntimeError):
            self.backup.execute("manual")
        self.assertEqual(1, len(self.objects))
        self.assertFalse(any(key.endswith("manifest.json") for key in self.objects))

    def test_size_mismatch_rejects_backup(self):
        original = self.run_command
        def incorrect_size(command, **kwargs):
            return "0" if command[0] == "aws" and "head-object" in command else original(command, **kwargs)
        self.backup.run.side_effect = incorrect_size
        with self.assertRaisesRegex(RuntimeError, "크기"):
            self.backup.execute("daily")
        self.assertFalse(self.backup.read_state("latest-success"))

    def test_standby_is_rejected_before_dump(self):
        self.primary = "t"
        with self.assertRaisesRegex(RuntimeError, "primary"):
            self.backup.execute("daily")
        self.assertFalse(any("pg_dump" in " ".join(c) for c in self.commands))

    def test_concurrent_backup_does_not_start_second_dump(self):
        with (self.backup.state / "backup.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual(1, self.backup.execute("daily"))
        self.assertFalse(self.commands)

    def test_physical_mode_checks_archive_before_backup(self):
        self.backup.mode = "physical"
        self.assertEqual(0, self.backup.execute("diff"))
        text = [" ".join(c) for c in self.commands]
        self.assertLess(next(i for i, c in enumerate(text) if c.endswith(" check")),
                        next(i for i, c in enumerate(text) if c.endswith(" backup")))

    def test_archive_check_failure_does_not_claim_success(self):
        self.backup.mode = "physical"
        self.failure = " check"
        with self.assertRaises(RuntimeError):
            self.backup.execute("full")
        self.assertFalse(self.backup.read_state("latest-success"))

    def test_monitor_records_failure_and_recovery(self):
        self.assertEqual(1, self.backup.monitor())
        self.assertTrue(self.backup.read_state("monitor")["problems"])
        self.assertIn("checked_at", self.backup.read_state("monitor"))
        self.backup.write_state("latest-success", {"completed_at": time.time()})
        self.assertEqual(0, self.backup.monitor())
        self.assertEqual([], self.backup.read_state("monitor")["problems"])

    def test_wal_backlog_fails_monitor_while_backup_is_fresh(self):
        self.backup.mode = "physical"
        for kind in ("latest-success", "full", "logical"):
            self.backup.write_state(kind, {"completed_at": time.time()})
        self.archive_age = "700"
        self.assertEqual(1, self.backup.monitor())
        self.assertIn("WAL", "; ".join(self.backup.read_state("monitor")["problems"]))

    def test_placeholder_configuration_is_rejected(self):
        config = self.root / "backup.ini"
        config.write_text(config.read_text().replace("test-bucket", "REPLACE_WITH_BACKUP_BUCKET"))
        with self.assertRaises(ValueError):
            MODULE.Backup(config)

    def test_missed_weekly_full_is_retried_on_next_scheduled_run(self):
        self.backup.mode = "physical"
        self.backup.write_state("full", {"completed_at": time.time() - 8 * 86400})
        with patch.object(MODULE, "Backup", return_value=self.backup), \
             patch.object(self.backup, "execute", return_value=0) as execute, \
             patch("sys.argv", ["backup.py", "scheduled"]):
            self.assertEqual(0, MODULE.main())
            execute.assert_called_once_with("full")

    def test_command_error_keeps_details_out_of_error_message(self):
        result = subprocess.CompletedProcess(["docker"], 1, stdout=b"", stderr=b"private-error-detail")
        with patch.object(MODULE.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as error:
                MODULE.Backup.run(self.backup, ["docker", "compose"])
        self.assertNotIn("private-error-detail", str(error.exception))
        self.assertEqual(b"private-error-detail", (self.backup.state / "last-command-error.log").read_bytes())


if __name__ == "__main__":
    unittest.main()
