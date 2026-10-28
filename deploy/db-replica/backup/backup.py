#!/usr/bin/env python3
"""Local logical backups from a PostgreSQL standby. Standard library + Docker Compose."""
import argparse
import configparser
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from zoneinfo import ZoneInfo

FILES = ("database.dump", "globals.sql")


class CommandError(RuntimeError):
    def __init__(self, message, stderr):
        super().__init__(message)
        self.stderr = stderr


class Backup:
    def __init__(self, filename):
        self.config = configparser.ConfigParser(interpolation=None)
        if not self.config.read(filename):
            raise ValueError("backup.ini를 읽을 수 없습니다")
        options = self.config["backup"]
        self.release = options["release_reference"]
        if not self.release or "REPLACE_WITH" in self.release:
            raise ValueError("현재 배포 버전 정보를 먼저 설정하세요")
        self.directory = Path(options["compose_dir"]).resolve()
        self.state = Path(options["state_dir"]).resolve()
        if self.state == Path("/"):
            raise ValueError("파일시스템 루트를 백업 경로로 사용할 수 없습니다")
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.compose = ["docker", "compose", "--project-directory", str(self.directory), "-p", options["compose_project"]]
        for name in options["compose_files"].splitlines():
            self.compose.extend(["-f", str(self.directory / name.strip())])
        self.service, self.user, self.database = options["service"], options["db_user"], options["db_name"]

    def run(self, command, *, output=None, input_file=None, timeout=7200):
        result = subprocess.run(command, cwd=self.directory, stdout=output or subprocess.PIPE,
                                stdin=input_file, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            diagnostic = self.state / "last-command-error.log"
            diagnostic.write_bytes(result.stderr[-16384:])
            raise CommandError(f"{command[0]} 실행 실패 (rc={result.returncode}); {diagnostic} 확인", result.stderr)
        return result.stdout.decode().strip() if result.stdout is not None else ""

    def db(self, *command, **kwargs):
        return self.run(self.compose + ["exec", "-T", "-u", "postgres", self.service] + list(command), **kwargs)

    def sql(self, query):
        return self.db("psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-U", self.user,
                       "-d", self.database, "-c", query, timeout=30)

    @staticmethod
    def write_json(path, value):
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        os.replace(temporary, path)

    def write_state(self, name, value):
        self.write_json(self.state / (name + ".json"), value)

    def read_state(self, name):
        path = self.state / (name + ".json")
        return json.loads(path.read_text()) if path.exists() else {}

    def standby(self):
        status = json.loads(self.sql("""
            SELECT json_build_object(
                'in_recovery', pg_is_in_recovery(),
                'checked_at', clock_timestamp(),
                'replay_timestamp', pg_last_xact_replay_timestamp(),
                'replay_age_seconds', EXTRACT(EPOCH FROM clock_timestamp() - pg_last_xact_replay_timestamp()),
                'wal_receiver_status', (SELECT status FROM pg_stat_wal_receiver LIMIT 1))
            """))
        if status["in_recovery"] is not True:
            raise RuntimeError("standby가 아닙니다. primary에서는 백업을 실행하지 않습니다")
        return status

    def freshness_problems(self, status):
        problems = []
        if status.get("wal_receiver_status") != "streaming":
            problems.append("복제 수신 상태가 streaming이 아닙니다")
        age = status.get("replay_age_seconds")
        if age is None or age > self.config["monitor"].getfloat("max_replay_age_seconds"):
            problems.append("마지막 트랜잭션 재생 시각이 없거나 허용 시간을 초과했습니다")
        return problems

    def disk_usage(self):
        usage = shutil.disk_usage(self.state)
        database = float(self.db("sh", "-ec", 'df -P "$PGDATA" | awk \'NR == 2 {gsub(/%/, "", $5); print $5}\'', timeout=30))
        return {"backup_percent": 100 * (usage.total - usage.free) / usage.total, "database_percent": database}

    def require_space(self):
        usage = self.disk_usage()
        if max(usage.values()) >= self.config["monitor"].getfloat("max_disk_used_percent"):
            raise RuntimeError("디스크 사용률이 한도 이상이므로 백업을 건너뜁니다: " + json.dumps(usage))
        return usage

    @staticmethod
    def digest(path):
        result = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                result.update(block)
        return result.hexdigest()

    @classmethod
    def verify_files(cls, directory, manifest, hashes=True):
        if set(manifest["files"]) != set(FILES):
            raise ValueError("백업 파일 목록이 올바르지 않습니다")
        for name in FILES:
            path, expected = directory / name, manifest["files"][name]
            if path.is_symlink() or not path.is_file() or path.stat().st_size != expected["bytes"]:
                raise ValueError("백업 파일 크기 검증 실패: " + name)
            if hashes and cls.digest(path) != expected["sha256"]:
                raise ValueError("백업 SHA256 검증 실패: " + name)

    def publish(self, directory, kind, manifest):
        self.verify_files(directory, manifest)
        for name in FILES:
            with (directory / name).open("rb") as stream:
                os.fsync(stream.fileno())
        # The final file is the completion marker. Publish the set by atomic rename.
        self.write_json(directory / "manifest.json", manifest)
        identifier = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        destination = self.state / kind / identifier
        directory.rename(destination)
        for path in (destination, destination.parent):
            descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        return destination

    def category(self, kind):
        if kind not in ("daily", "weekly"):
            raise ValueError("잘못된 백업 종류입니다")
        base = self.state / kind
        if base.is_symlink() or base.resolve().parent != self.state:
            raise ValueError("백업 폴더 밖의 경로는 사용하지 않습니다")
        base.mkdir(exist_ok=True, mode=0o700)
        return base

    def dump(self, kind):
        base = self.category(kind)
        for attempt in range(2):
            before = self.standby()
            self.require_space()
            with tempfile.TemporaryDirectory(prefix=".incomplete-", dir=base) as temporary:
                directory = Path(temporary)
                try:
                    with (directory / "database.dump").open("wb") as stream:
                        self.db("pg_dump", "-U", self.user, "-d", self.database, "-Fc", output=stream)
                    if not (directory / "database.dump").stat().st_size:
                        raise ValueError("빈 DB 덤프입니다")
                    with (directory / "database.dump").open("rb") as stream:
                        self.db("pg_restore", "--list", input_file=stream)
                    with (directory / "globals.sql").open("wb") as stream:
                        self.db("pg_dumpall", "-U", self.user, "-l", self.database,
                                "--globals-only", "--no-role-passwords", output=stream)
                    after = self.standby()  # Refuse a promotion during the dump too.
                    self.require_space()
                    manifest = {"kind": kind, "completed_at": datetime.now(timezone.utc).isoformat(),
                                "database": self.database, "server_version": self.sql("SHOW server_version"),
                                "release_reference": self.release, "dump_attempts": attempt + 1,
                                "replication_before": before, "replication_after": after,
                                "freshness_problems": list(dict.fromkeys(self.freshness_problems(before) + self.freshness_problems(after))),
                                "files": {}}
                    for name in FILES:
                        path = directory / name
                        manifest["files"][name] = {"bytes": path.stat().st_size, "sha256": self.digest(path)}
                    return self.publish(directory, kind, manifest)
                except CommandError as error:
                    if attempt != 0 or b"conflict with recovery" not in error.stderr.lower():
                        raise
                    print("RETRY: 복제 충돌로 취소된 덤프를 5초 뒤 1회 재시도합니다", flush=True)
                    time.sleep(5)

    def weekly_copy(self, daily):
        self.require_space()
        manifest = json.loads((daily / "manifest.json").read_text())
        self.verify_files(daily, manifest)
        base = self.category("weekly")
        with tempfile.TemporaryDirectory(prefix=".incomplete-", dir=base) as temporary:
            directory = Path(temporary)
            for name in FILES:
                shutil.copyfile(daily / name, directory / name)
            self.require_space()
            manifest.update(kind="weekly", copied_from=str(daily))
            return self.publish(directory, "weekly", manifest)

    def prune(self, kind):
        base = self.category(kind)
        completed = []
        for path in base.iterdir():
            if path.is_symlink() or not path.is_dir() or not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", path.name):
                continue
            marker = path / "manifest.json"
            if marker.is_symlink() or not marker.is_file():
                continue
            try:
                manifest = json.loads(marker.read_text())
                self.verify_files(path, manifest, hashes=False)
                if manifest["kind"] != kind:
                    continue
                completed.append((datetime.fromisoformat(manifest["completed_at"]).timestamp(), path))
            except (ValueError, KeyError, TypeError):
                continue
        keep = self.config["backup"].getint("retain_" + kind)
        if keep < 1:
            raise ValueError("최소 1개 백업은 보관해야 합니다")
        for _, path in sorted(completed, reverse=True)[keep:]:
            if path.resolve().parent != base.resolve():
                raise ValueError("백업 폴더 밖의 경로는 삭제하지 않습니다")
            shutil.rmtree(path)

    def record_success(self, kind, location, started):
        result = {"kind": kind, "completed_at": time.time(), "location": str(location),
                  "release_reference": self.release, "duration_seconds": int(time.time() - started)}
        self.write_state(kind, result)
        self.write_state("latest-success", result)
        self.prune(kind)
        return result

    def execute(self, kind, include_weekly=False):
        with (self.state / "backup.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print("SKIP: 이전 백업 실행 중", flush=True)
                return 1
            started = time.time()
            self.write_state("attempt", {"kind": kind, "started_at": started})
            try:
                location = self.dump(kind)
                result = self.record_success(kind, location, started)
                if include_weekly:
                    self.record_success("weekly", self.weekly_copy(location), started)
                self.write_state("attempt", dict(result, status="OK"))
                print(json.dumps(result, ensure_ascii=False), flush=True)
                return 0
            except Exception:
                self.write_state("attempt", {"kind": kind, "status": "FAILED", "failed_at": time.time()})
                raise

    def monitor(self):
        problems = []
        settings = self.config["monitor"]
        for kind, limit in (("daily", settings.getfloat("max_backup_age_hours") * 3600),
                            ("weekly", settings.getfloat("max_weekly_age_days") * 86400)):
            latest = self.read_state(kind)
            if time.time() - latest.get("completed_at", 0) > limit:
                problems.append(kind + " 백업이 없거나 허용 시간을 초과했습니다")
            if latest:
                try:
                    directory = Path(latest["location"])
                    manifest = json.loads((directory / "manifest.json").read_text())
                    self.verify_files(directory, manifest, hashes=False)
                    problems.extend(kind + " 덤프 시점: " + message for message in manifest["freshness_problems"])
                except (OSError, ValueError, KeyError, TypeError):
                    problems.append(kind + " 최근 백업 세트를 확인할 수 없습니다")
        try:
            problems.extend(self.freshness_problems(self.standby()))
            self.require_space()
        except Exception as error:
            problems.append(str(error))
        result = {"problems": list(dict.fromkeys(problems)), "checked_at": time.time()}
        self.write_state("monitor", result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        return 1 if problems else 0


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="/home/ubuntu/yon-db-replica/backup/backup.ini")
    parser.add_argument("action", choices=("scheduled", "daily", "weekly", "monitor"))
    args = parser.parse_args()
    backup = Backup(args.config)
    if args.action == "monitor":
        return backup.monitor()
    return backup.execute("daily" if args.action == "scheduled" else args.action,
                          include_weekly=args.action == "scheduled" and datetime.now(ZoneInfo("Asia/Seoul")).weekday() == 6)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print("ERROR: " + str(error), flush=True)
        raise SystemExit(1)
