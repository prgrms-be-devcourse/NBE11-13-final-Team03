#!/usr/bin/env python3
"""Host-side PostgreSQL backups. Uses Docker, AWS CLI and Python's standard library."""
import argparse
import configparser
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
from zoneinfo import ZoneInfo


class Backup:
    def __init__(self, filename):
        config = configparser.ConfigParser(interpolation=None)
        if not config.read(filename):
            raise ValueError("backup.ini를 읽을 수 없습니다")
        self.config = config
        self.options = config["backup"]
        self.mode = self.options["mode"]
        if self.mode not in ("logical", "physical"):
            raise ValueError("mode는 logical 또는 physical이어야 합니다")
        self.bucket = self.options["bucket"]
        self.release = self.options["release_reference"]
        if any("REPLACE_WITH" in value or not value for value in (self.bucket, self.release)):
            raise ValueError("버킷과 배포 버전 정보를 먼저 설정하세요")
        self.directory = Path(self.options["compose_dir"]).resolve()
        self.state = Path(self.options["state_dir"])
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.compose = ["docker", "compose", "--project-directory", str(self.directory)]
        for filename in self.options["compose_files"].splitlines():
            self.compose.extend(["-f", str(self.directory / filename.strip())])
        self.service = self.options.get("service", "postgres-db")

    def run(self, command, *, output=None, input_file=None, timeout=7200):
        result = subprocess.run(command, cwd=self.directory, stdout=output or subprocess.PIPE,
                                stdin=input_file, stderr=subprocess.PIPE, timeout=timeout,
                                env=dict(os.environ, AWS_PAGER="", AWS_CLI_AUTO_PROMPT="off"))
        if result.returncode:
            # Keep detailed diagnostics in the private state directory.
            diagnostic = self.state / "last-command-error.log"
            diagnostic.write_bytes(result.stderr[-16384:])
            raise RuntimeError(f"{command[0]} 실행 실패 (rc={result.returncode}); {diagnostic} 확인")
        return result.stdout.decode().strip() if result.stdout is not None else ""

    def db(self, *command, **kwargs):
        return self.run(self.compose + ["exec", "-T", "-u", "postgres", self.service] + list(command), **kwargs)

    def sql(self, query):
        return self.db("sh", "-ec", 'exec psql -X -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
                       "sh", query, timeout=30)

    def write_state(self, name, value):
        with tempfile.NamedTemporaryFile(mode="w", dir=self.state, delete=False) as stream:
            json.dump(value, stream)
            temporary = Path(stream.name)
        os.replace(temporary, self.state / (name + ".json"))

    def read_state(self, name):
        path = self.state / (name + ".json")
        return json.loads(path.read_text()) if path.exists() else {}

    def primary(self):
        if self.sql("SELECT pg_is_in_recovery()") != "f":
            raise RuntimeError("primary DB가 아닙니다. 복제본에서는 이 작업을 실행하지 마세요")

    def aws(self, *command):
        return self.run(["aws", *command, "--region", self.options.get("region", "ap-northeast-2")], timeout=1800)

    def upload(self, path, key):
        self.aws("s3", "cp", str(path), "s3://" + self.bucket + "/" + key, "--only-show-errors")
        size = self.aws("s3api", "head-object", "--bucket", self.bucket, "--key", key,
                        "--query", "ContentLength", "--output", "text")
        if int(size) != path.stat().st_size:
            raise RuntimeError("S3 업로드 크기 검증 실패")

    def logical(self, kind):
        identifier = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        key = kind + "/" + identifier
        with tempfile.TemporaryDirectory(prefix="dump-", dir=self.state) as directory:
            root = Path(directory)
            dump = root / "database.dump"
            roles = root / "globals.sql"
            with dump.open("wb") as stream:
                self.db("sh", "-ec", 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc', output=stream)
            if not dump.stat().st_size:
                raise RuntimeError("빈 DB 덤프입니다")
            with dump.open("rb") as stream:
                self.db("pg_restore", "--list", input_file=stream)
            with roles.open("wb") as stream:
                self.db("sh", "-ec", 'exec pg_dumpall -U "$POSTGRES_USER" --globals-only --no-role-passwords', output=stream)
            manifest = {"completed_at": datetime.now(timezone.utc).isoformat(), "release_reference": self.release,
                        "database": self.sql("SELECT current_database()"),
                        "server_version": self.sql("SHOW server_version"), "kind": kind, "files": {}}
            for path in (dump, roles):
                digest = self.digest(path)
                manifest["files"][path.name] = {"bytes": path.stat().st_size, "sha256": digest}
                self.upload(path, key + "/" + path.name)
            marker = root / "manifest.json"
            marker.write_text(json.dumps(manifest, indent=2))
            # A set is usable only after both files exist and this final manifest is uploaded.
            self.upload(marker, key + "/manifest.json")
        return "s3://" + self.bucket + "/" + key

    @staticmethod
    def digest(path):
        result = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                result.update(block)
        return result.hexdigest()

    def execute(self, kind):
        with (self.state / "backup.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print("SKIP " + kind + ": 이전 백업 실행 중. 작업 완료 후 다시 실행하세요", flush=True)
                return 1
            started = time.time()
            self.write_state("attempt", {"kind": kind, "started_at": started})
            try:
                self.primary()
                if kind in ("daily", "logical", "manual"):
                    location = self.logical(kind)
                else:
                    if self.mode != "physical":
                        raise ValueError("full/diff는 physical 설정과 초기화가 필요합니다")
                    self.db("pgbackrest", "--stanza=yon", "check")
                    self.db("pgbackrest", "--stanza=yon", "--type=" + kind, "backup")
                    location = self.db("pgbackrest", "--stanza=yon", "--output=json", "info")
                result = {"kind": kind, "completed_at": time.time(), "location": location,
                          "release_reference": self.release, "duration_seconds": int(time.time() - started)}
                self.write_state(kind, result)
                if kind in ("daily", "full", "diff"):
                    self.write_state("latest-success", result)
                self.write_state("attempt", dict(result, status="OK"))
                print(json.dumps(result, ensure_ascii=False), flush=True)
                return 0
            except Exception:
                self.write_state("attempt", {"kind": kind, "status": "FAILED", "failed_at": time.time()})
                raise

    def monitor(self):
        problems = []
        settings = self.config["monitor"]
        latest = self.read_state("latest-success")
        if time.time() - latest.get("completed_at", 0) > settings.getfloat("max_backup_age_hours") * 3600:
            problems.append("정기 백업 성공 기록이 없거나 설정한 허용 시간을 초과했습니다")
        if self.mode == "physical":
            if time.time() - self.read_state("full").get("completed_at", 0) > 8 * 86400:
                problems.append("주간 full 백업 성공 기록이 없거나 8일을 초과했습니다")
            if time.time() - self.read_state("logical").get("completed_at", 0) > 40 * 86400:
                problems.append("월간 논리 백업 성공 기록이 없거나 40일을 초과했습니다")
        try:
            self.primary()
            used = int(self.db("sh", "-ec", 'df -P "$PGDATA" | awk \'NR == 2 {gsub(/%/, "", $5); print $5}\'', timeout=30))
            if used >= settings.getint("max_disk_used_percent"):
                problems.append(f"DB 데이터 디스크 사용률 {used}%")
            if self.mode == "physical":
                if self.sql("SHOW archive_mode") != "on":
                    problems.append("WAL archive_mode가 on이 아닙니다")
                age = float(self.sql("SELECT COALESCE(EXTRACT(EPOCH FROM now() - min((pg_stat_file('pg_wal/archive_status/' || name)).modification)), 0) FROM pg_ls_dir('pg_wal/archive_status') AS name WHERE name LIKE '%.ready'"))
                if age > settings.getfloat("max_archive_queue_age_seconds"):
                    problems.append(f"WAL 아카이브 대기 {int(age)}초")
        except Exception as error:
            problems.append(str(error))
        self.write_state("monitor", {"problems": problems, "checked_at": time.time()})
        print(json.dumps({"problems": problems}, ensure_ascii=False), flush=True)
        return 1 if problems else 0


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="/home/ubuntu/yon-db/backup/backup.ini")
    parser.add_argument("action", choices=("scheduled", "monthly", "daily", "logical", "manual", "full", "diff", "monitor"))
    args = parser.parse_args()
    backup = Backup(args.config)
    action = args.action
    if action == "scheduled":
        full_due = (datetime.now(ZoneInfo("Asia/Seoul")).weekday() == 6
                    or time.time() - backup.read_state("full").get("completed_at", 0) >= 7 * 86400)
        action = "daily" if backup.mode == "logical" else ("full" if full_due else "diff")
    if action == "monthly":
        if backup.mode != "physical":
            print("SKIP monthly: 일일 논리 백업 운영 중")
            return 0
        action = "logical"
    return backup.monitor() if action == "monitor" else backup.execute(action)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print("ERROR: " + str(error), flush=True)
        raise SystemExit(1)
