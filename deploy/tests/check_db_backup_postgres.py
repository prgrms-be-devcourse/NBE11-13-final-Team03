"""Disposable PostgreSQL 16 + LocalStack S3 backup/restore check; never uses AWS.

Linux CI: python3 deploy/tests/check_db_backup_postgres.py
Windows: supply --runner-image with a Linux image containing Python, Docker Compose and AWS CLI.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(*command, input=None, timeout=300):
    result = subprocess.run(command, input=input, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(" ".join(command[:4]) + "\n" + (result.stdout + result.stderr).decode(errors="replace")[-5000:])
    return result.stdout.decode().strip()


def wait(command):
    for _ in range(60):
        try:
            return command()
        except RuntimeError:
            time.sleep(1)
    raise RuntimeError("Local PostgreSQL/S3 did not become ready")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-image")
    parser.add_argument("--runner-image")
    args = parser.parse_args()
    identifier = "yon-backup-check-" + uuid.uuid4().hex[:8]
    image = args.postgres_image or identifier
    if not args.postgres_image:
        run("docker", "build", "-t", image, "-f", str(ROOT / "deploy/db/backup/Dockerfile"), str(ROOT / "deploy/db"))
    validation = ROOT / ".validation"
    validation.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=identifier, dir=validation) as directory:
        directory = Path(directory)
        assert directory.resolve().parent == validation.resolve()
        network = identifier
        s3 = identifier + "-s3"
        database = identifier + "-db"
        logical = identifier + "-logical"
        latest = identifier + "-latest"
        pitr = identifier + "-pitr"
        volumes = [identifier + suffix for suffix in ("-data", "-state", "-logical-data", "-latest-data", "-pitr-data")]
        try:
            run("docker", "network", "create", network)
            run("docker", "run", "-d", "--name", s3, "--network", network, "--network-alias", "s3",
                "-e", "SERVICES=s3", "-p", "127.0.0.1::4566", "-p", "127.0.0.1::8476", "localstack/localstack:4.12.0")
            wait(lambda: run("docker", "exec", s3, "awslocal", "s3api", "list-buckets", timeout=10))
            run("docker", "exec", s3, "awslocal", "s3api", "create-bucket", "--bucket", "yon-backup-test")
            webhook = "from http.server import HTTPServer,BaseHTTPRequestHandler\nclass H(BaseHTTPRequestHandler):\n def do_POST(self):\n  import json\n  body=self.rfile.read(int(self.headers['Content-Length']));json.loads(body)\n  with open('/tmp/notifications.jsonl','ab') as f:f.write(body+b'\\n')\n  self.send_response(200);self.end_headers();self.wfile.write(b'ok')\nHTTPServer(('0.0.0.0',8476),H).serve_forever()"
            run("docker", "exec", "-d", s3, "python3", "-c", webhook)
            compose = {"name": identifier, "services": {"postgres-db": {
                "image": image, "container_name": database,
                "environment": {"POSTGRES_PASSWORD": "disposable-test-only", "POSTGRES_DB": "yon"},
                "cpus": 2, "mem_limit": "2g", "volumes": [volumes[0] + ":/var/lib/postgresql/data"],
                "command": ["postgres", "-c", "archive_mode=on", "-c", "archive_timeout=300", "-c", "archive_command=pgbackrest --stanza=yon archive-push %p"],
                "networks": ["backup-test"]}}, "volumes": {volumes[0]: {"name": volumes[0]}},
                "networks": {"backup-test": {"external": True, "name": network}}}
            compose_file = directory / "compose.json"
            compose_file.write_text(json.dumps(compose))
            run("docker", "compose", "-f", str(compose_file), "up", "-d")
            wait(lambda: run("docker", "exec", database, "sh", "-ec", 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres -d yon'))
            configuration = (ROOT / "deploy/db/backup/pgbackrest.conf.example").read_text()
            configuration = configuration.replace("REPLACE_WITH_BACKUP_BUCKET", "yon-backup-test").replace("repo1-s3-key-type=auto", "repo1-s3-key-type=shared\nrepo1-s3-key=test\nrepo1-s3-key-secret=test\nrepo1-s3-uri-style=path\nrepo1-storage-port=4566\nrepo1-storage-verify-tls=n")
            configuration = configuration.replace("s3.ap-northeast-2.amazonaws.com", "s3")
            conf = directory / "pgbackrest.conf"
            conf.write_text(configuration)
            run("docker", "exec", database, "mkdir", "-p", "/etc/pgbackrest")
            run("docker", "cp", str(conf), database + ":/etc/pgbackrest/pgbackrest.conf")
            def sql(container, statement, user="postgres"):
                return run("docker", "exec", "-u", "postgres", container, "psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", "yon", "-c", statement)
            def backrest(container, *options):
                return run("docker", "exec", "-u", "postgres", container, "pgbackrest", "--stanza=yon", *options)
            wait(lambda: sql(database, "SELECT 1"))
            sql(database, """
                CREATE ROLE application_user LOGIN; CREATE ROLE notification_user LOGIN;
                CREATE SCHEMA application AUTHORIZATION application_user;
                CREATE SCHEMA notification AUTHORIZATION notification_user;
                SET ROLE application_user;
                CREATE TABLE application.raw_external_record(id bigint GENERATED ALWAYS AS IDENTITY, source_id bigint NOT NULL, payload text, PRIMARY KEY(id,source_id)) PARTITION BY LIST(source_id);
                CREATE TABLE application.raw_external_record_youth_policy PARTITION OF application.raw_external_record FOR VALUES IN (1);
                CREATE TABLE application.raw_external_record_employment24 PARTITION OF application.raw_external_record FOR VALUES IN (2);
                CREATE TABLE application.user_behavior_events(id bigint GENERATED ALWAYS AS IDENTITY, received_at timestamp NOT NULL, PRIMARY KEY(id,received_at)) PARTITION BY RANGE(received_at);
                CREATE TABLE application.user_behavior_events_202610 PARTITION OF application.user_behavior_events FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
                INSERT INTO application.raw_external_record(source_id,payload) SELECT 1+(id%2), 'payload-'||id FROM generate_series(1,100) id;
                INSERT INTO application.user_behavior_events(received_at) SELECT '2026-10-27'::timestamp FROM generate_series(1,100);
                GRANT USAGE ON SCHEMA application TO notification_user;
                GRANT SELECT ON application.raw_external_record TO notification_user;
                RESET ROLE; SET ROLE notification_user;
                CREATE TABLE notification.notifications(id bigint PRIMARY KEY, body text);
                INSERT INTO notification.notifications VALUES (1,'notification');
            """)
            if args.runner_image:
                inside = PurePosixPath("/workspace") / directory.relative_to(ROOT).as_posix()
                endpoint = "http://127.0.0.1:4566"
                hook = "http://127.0.0.1:8476"
                state = "/backup-state"
            else:
                inside = directory
                endpoint = "http://" + run("docker", "port", s3, "4566/tcp")
                hook = "http://" + run("docker", "port", s3, "8476/tcp")
                state = str(directory / "state")
            (directory / "webhook").write_text(hook)
            config = (ROOT / "deploy/db/backup/backup.ini.example").read_text()
            config = config.replace("REPLACE_WITH_BACKUP_BUCKET", "yon-backup-test").replace("REPLACE_WITH_DEPLOYED_RELEASE", "integration-test")
            config = config.replace("/home/ubuntu/yon-db/backup/slack-webhook", str(inside / "webhook"))
            config = config.replace("/home/ubuntu/yon-db", str(inside)).replace("/var/lib/yon-backup", state)
            config = config.replace("compose_files=docker-compose.yml", "compose_files=compose.json").replace("max_disk_used_percent=80", "max_disk_used_percent=100")
            (directory / "backup.ini").write_text(config)
            def backup(action):
                command = ["--config", str(inside / "backup.ini"), action]
                if args.runner_image:
                    return run("docker", "run", "--rm", "--network", "container:" + s3,
                               "--mount", "type=bind,source=" + str(ROOT) + ",target=/workspace,readonly",
                               "-v", "/var/run/docker.sock:/var/run/docker.sock", "-v", volumes[1] + ":/backup-state",
                               "-e", "AWS_ACCESS_KEY_ID=test", "-e", "AWS_SECRET_ACCESS_KEY=test", "-e", "AWS_ENDPOINT_URL=" + endpoint,
                               "--entrypoint", "python3", args.runner_image, "/workspace/deploy/db/backup/backup.py", *command)
                import os
                result = subprocess.run([sys.executable, str(ROOT / "deploy/db/backup/backup.py"), *command],
                                        env=dict(os.environ, AWS_ACCESS_KEY_ID="test", AWS_SECRET_ACCESS_KEY="test", AWS_ENDPOINT_URL=endpoint),
                                        capture_output=True, timeout=300)
                if result.returncode:
                    raise RuntimeError(result.stdout.decode() + result.stderr.decode())
                return result.stdout.decode().strip()
            # Entire logical workflow: real pg_dump, global roles, S3 upload and marker hashes.
            daily = json.loads(backup("daily"))
            prefix = daily["location"].split("yon-backup-test/", 1)[1]
            for name in ("database.dump", "globals.sql", "manifest.json"):
                run("docker", "exec", s3, "awslocal", "s3", "cp", "s3://yon-backup-test/" + prefix + "/" + name, "/tmp/" + name)
                run("docker", "cp", s3 + ":/tmp/" + name, str(directory / name))
            manifest = json.loads((directory / "manifest.json").read_text())
            for name, details in manifest["files"].items():
                assert hashlib.sha256((directory / name).read_bytes()).hexdigest() == details["sha256"]
            run("docker", "run", "-d", "--name", logical, "--network", network,
                "-e", "POSTGRES_USER=restore_admin", "-e", "POSTGRES_PASSWORD=disposable-test-only", "-e", "POSTGRES_DB=yon",
                "-v", volumes[2] + ":/var/lib/postgresql/data", image)
            wait(lambda: run("docker", "exec", logical, "sh", "-ec", 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U restore_admin -d yon'))
            run("docker", "exec", "-i", logical, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "restore_admin", "-d", "yon", input=(directory / "globals.sql").read_bytes())
            run("docker", "exec", "-i", logical, "pg_restore", "--exit-on-error", "-U", "restore_admin", "-d", "yon", input=(directory / "database.dump").read_bytes())
            assert sql(logical, "SELECT count(*) FROM application.raw_external_record") == "100"
            assert sql(logical, "SELECT count(*) FROM application.user_behavior_events") == "100"
            assert sql(logical, "SELECT count(*) FROM notification.notifications") == "1"
            assert sql(logical, "SELECT tableowner FROM pg_tables WHERE schemaname='application' AND tablename='raw_external_record'") == "application_user"
            assert sql(logical, "SELECT has_table_privilege('notification_user','application.raw_external_record','SELECT')") == "t"
            # Physical full + diff + continuous WAL; restore both latest and a point before the extra row.
            backrest(database, "stanza-create")
            backrest(database, "check")
            (directory / "backup.ini").write_text(config.replace("mode=logical", "mode=physical"))
            backup("full")
            info = json.loads(backrest(database, "--output=json", "info"))[0]
            full_label = info["backup"][-1]["label"]
            target = sql(database, "SELECT clock_timestamp()")
            sql(database, "INSERT INTO application.raw_external_record(source_id,payload) VALUES (1,'after-target'); SELECT pg_switch_wal()")
            backup("diff")
            backup("monthly")
            backup("monitor")
            info = json.loads(backrest(database, "--output=json", "info"))[0]
            assert [item["type"] for item in info["backup"]] == ["full", "diff"]
            for container, volume, options, count in (
                (latest, volumes[3], [], "101"),
                (pitr, volumes[4], ["--set=" + full_label, "--type=time", "--target=" + target, "--target-action=promote"], "100")):
                run("docker", "run", "-d", "--name", container, "--network", network,
                    "-v", volume + ":/var/lib/postgresql/data", image, "sleep", "infinity")
                run("docker", "exec", container, "mkdir", "-p", "/etc/pgbackrest")
                run("docker", "cp", str(conf), container + ":/etc/pgbackrest/pgbackrest.conf")
                backrest(container, *options, "restore")
                run("docker", "exec", "-u", "postgres", container, "pg_ctl", "-D", "/var/lib/postgresql/data", "-l", "/tmp/recovery.log", "-o", "-c archive_mode=off -c listen_addresses=''", "-w", "start")
                assert sql(container, "SELECT count(*) FROM application.raw_external_record") == count
                assert sql(container, "SELECT count(*) FROM application.user_behavior_events") == "100"
                assert sql(container, "SELECT count(*) FROM notification.notifications") == "1"
                assert sql(container, "SELECT relkind FROM pg_class WHERE oid='application.raw_external_record'::regclass") == "p"
            print(json.dumps({"logical_restore": "100 raw + 100 behavior + 1 notification; owners/grants preserved",
                              "physical": [item["type"] for item in info["backup"]],
                              "latest_raw_count": 101, "pitr_raw_count": 100, "monitor": "passed"}, indent=2))
        except Exception:
            for container in (database, latest, pitr):
                result = subprocess.run(["docker", "logs", "--tail", "15", container], capture_output=True)
                print(result.stderr.decode(errors="replace"), file=sys.stderr)
            raise
        finally:
            # Delete only resources whose names were created by this invocation.
            assert all(name.startswith(identifier) for name in [s3, database, logical, latest, pitr, *volumes])
            for container in (database, logical, latest, pitr, s3):
                subprocess.run(["docker", "rm", "-f", container], capture_output=True)
            for volume in volumes:
                subprocess.run(["docker", "volume", "rm", volume], capture_output=True)
            subprocess.run(["docker", "network", "rm", network], capture_output=True)


if __name__ == "__main__":
    main()
