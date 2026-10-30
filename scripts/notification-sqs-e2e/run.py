"""One owned Docker project: actual Boot JARs, gRPC, PostgreSQL and AWS SDK/SQS.

Run from any directory: python scripts/notification-sqs-e2e/run.py
Only Python's standard library and Docker Compose are required by the harness.
"""
import argparse
import base64
import hmac
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import subprocess
import time
import traceback
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
REGION = "ap-northeast-2"
USER_QUEUE = "yon-sqs-e2e-notifications"
ADMIN_QUEUE = "yon-sqs-e2e-collection"
KEY = base64.b64encode(b"a" * 64).decode()
SCENARIOS = {
    "startup and authentication boundaries",
    "100 plus remainder, page retry, final completion only",
    "independent normalization result",
    "failure, Slack rollback/redelivery, full manual retry",
    "administrator duplicate has no extra Slack/user inbox",
    "user outbox, ownership, read and duplicate",
    "invalid message has no receipt/Slack and reaches DLQ",
}


def wait(description, condition, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(0.5)
    raise AssertionError(f"Timed out: {description}")


class Lab:
    def __init__(self, output):
        self.output = output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.project = "yon-service-e2e-" + uuid.uuid4().hex[:12]
        self.config = self.output / "compose.json"
        self.results = []
        self.backend = self.stub = None
        self.csrf = {}

    def command(self, *args, stdin=None, timeout=180):
        result = subprocess.run(args, input=stdin, text=True, encoding="utf-8", errors="replace",
                                capture_output=True, cwd=ROOT, timeout=timeout)
        with (self.output / "commands.log").open("a", encoding="utf-8") as log:
            log.write("$ " + " ".join(map(str, args)) + "\n" + result.stdout + result.stderr + "\n")
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {args[:4]}\n{result.stderr[-2000:]}")
        return result.stdout

    def compose(self, *args, **kwargs):
        return self.command("docker", "compose", "--env-file", os.devnull, "-p", self.project,
                            "-f", str(self.config), *args, **kwargs)

    def sql(self, statement, role="application", rows=True):
        password = f"e2e-{role}-only"
        prefix = f"SET search_path TO {role},pg_catalog;\n"
        if rows:
            statement = "SELECT COALESCE(json_agg(x),'[]'::json) FROM (" + statement + ") x;"
        result = self.compose("exec", "-T", "db", "env", "PGPASSWORD=" + password,
                              "psql", "-h", "127.0.0.1", "-U", role + "_user", "-d", "yon_sqs_e2e",
                              "-Atq", "-v", "ON_ERROR_STOP=1", stdin=prefix + statement)
        return json.loads(result) if rows else result

    def count(self, table, where="true", role="application"):
        return self.sql(f"SELECT count(*) AS n FROM {table} WHERE {where}", role)[0]["n"]

    def aws(self, *args):
        value = self.compose("exec", "-T", "sqs", "awslocal", "sqs", *args,
                             "--region", REGION, "--output", "json")
        return json.loads(value) if value.strip() else {}

    def queue(self, name):
        return "http://localhost:4566/000000000000/" + name

    def attrs(self, name):
        return self.aws("get-queue-attributes", "--queue-url", self.queue(name), "--attribute-names",
                        "ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible")["Attributes"]

    def empty(self, name):
        return all(int(n) == 0 for n in self.attrs(name).values())

    def send(self, name, body):
        return self.aws("send-message", "--queue-url", self.queue(name), "--message-body", json.dumps(body))

    def http(self, base, path, method="GET", body=None, user=None, role="USER", expected=200, csrf=True):
        headers = {}
        if user is not None:
            def encoded(value):
                return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")
            now = int(time.time())
            unsigned = encoded({"alg": "HS512", "typ": "JWT"}) + "." + encoded({
                "iss": "yon", "sub": str(user), "role": role, "token_type": "ACCESS",
                "session_id": str(uuid.uuid4()), "iat": now, "exp": now + 3600})
            signature = base64.urlsafe_b64encode(hmac.digest(base64.b64decode(KEY), unsigned.encode(), "sha512")).decode().rstrip("=")
            headers["Cookie"] = "access_token=" + unsigned + "." + signature
            if csrf and user in self.csrf:
                headers["Cookie"] += "; XSRF-TOKEN=" + self.csrf[user]
                headers["X-XSRF-TOKEN"] = self.csrf[user]
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(base + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            response = urllib.request.urlopen(request, timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read().decode()
            assert response.code == expected, (method, path, response.code, expected, content)
            for cookie in response.headers.get_all("Set-Cookie", []):
                parsed = SimpleCookie(cookie)
                if user is not None and "XSRF-TOKEN" in parsed:
                    self.csrf[user] = parsed["XSRF-TOKEN"].value
            return json.loads(content) if content else None

    def state(self):
        return self.http(self.stub, "/state")

    def control(self, **body):
        return self.http(self.stub, "/control", "POST", body)

    def record(self, name, evidence):
        assert name in SCENARIOS and name not in {row["scenario"] for row in self.results}, name
        self.results.append({"scenario": name, "status": "passed", "evidence": evidence})
        self.save()
        print("PASS: " + name, flush=True)

    def save(self):
        (self.output / "results.json").write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")

    def setup(self):
        # Compose resolves the existing bootstrap paths; no YAML library or copied infrastructure definition.
        config = json.loads(self.command("docker", "compose", "--env-file", os.devnull,
                                        "-f", str(HERE / "compose.yaml"), "config", "--format", "json"))
        services = config["services"]
        for name in ("db", "sqs"):
            services[name]["ports"] = []
        # Git on Windows may check out .sh as CRLF. Normalize only this run's disposable copies.
        for volume in services["db"]["volumes"]:
            if volume["source"].endswith(".sh"):
                source = Path(volume["source"])
                target = self.output / source.name
                target.write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))
                volume["source"] = str(target)
        for name, alias in (("backend-net", "backend"), ("data-net", "data"), ("notification-net", "notification")):
            services[name] = {"image": "alpine/socat:latest", "command": ["TCP-LISTEN:4566,fork,reuseaddr", "TCP:sqs:4566"],
                              "networks": {"default": {"aliases": [alias]}}, "depends_on": ["sqs"]}
        services["backend-net"]["ports"] = ["127.0.0.1::8080"]
        services["notification-net"]["ports"] = ["127.0.0.1::8000"]
        services["stubs"] = {"image": "python:3.14-slim", "network_mode": "service:notification-net",
                              "command": ["python", "/stubs.py"],
                              "volumes": [str(HERE / "stubs.py") + ":/stubs.py:ro"]}
        common = {"DB_URL": "jdbc:postgresql://db:5432/yon_sqs_e2e", "SPRING_PROFILES_ACTIVE": "database-boundary",
                  "APPLICATION_DB_PASSWORD": "e2e-application-only", "NOTIFICATION_DB_PASSWORD": "e2e-notification-only",
                  "AWS_ACCESS_KEY_ID": "test", "AWS_SECRET_ACCESS_KEY": "test", "AWS_EC2_METADATA_DISABLED": "true",
                  "DATA_SERVICE_INTERNAL_API_KEY": "e2e-data-only", "COLLECTION_SCHEDULE_ENABLED": "false",
                  "SPRING_TASK_SCHEDULING_POOL_SIZE": "4", "YON_NORMALIZATION_CONDITION_SET_BACKFILL_ENABLED": "false"}
        for name, module, jar, namespace in [("backend", "backend", "app.jar", "backend-net"),
                    ("data", "data-service", "app.jar", "data-net"),
                    ("notification", "notification-service", "notification-service.jar", "notification-net")]:
            services[name] = {"image": "eclipse-temurin:25-jre", "network_mode": "service:" + namespace,
                              "command": ["java", "-Xmx512m", "-jar", "/app.jar"],
                              "volumes": [str(ROOT / module / "build/libs" / jar) + ":/app.jar:ro"],
                              "environment": dict(common), "depends_on": [namespace, "db"]}
        services["backend"]["environment"].update({
            "KAKAO_CLIENT_ID": "e2e-placeholder", "KAKAO_CLIENT_SECRET": "e2e-placeholder", "JWT_SECRET_KEY": KEY,
            "INTERNAL_API_KEY": "e2e-internal-only", "DATA_SERVICE_GRPC_ADDRESS": "static://data:9092", "DB_REPLICA_URL": "",
            "NOTIFICATION_PUBLISHER_ENABLED": "true", "NOTIFICATION_PUBLISHER_TRANSPORT": "sqs",
            "NOTIFICATION_SQS_REGION": REGION, "NOTIFICATION_SQS_ENDPOINT": "http://localhost:4566",
            "NOTIFICATION_SQS_QUEUE_URL": self.queue(USER_QUEUE), "NOTIFICATION_FACADE_ENABLED": "true",
            "NOTIFICATION_BASE_URL": "http://notification:8081", "NOTIFICATION_INTERNAL_API_KEY": "e2e-notification-only",
            "YON_TRACKING_DATE_ENABLED": "false", "YON_TRACKING_FANOUT_ENABLED": "false", "YON_ELIGIBILITY_BATCH_ENABLED": "false",
            "YON_NOTIFICATION_PUBLISHER_POLL_DELAY_MS": "500"})
        services["data"]["environment"].update({
            "YOUTH_POLICY_API_KEY": "e2e-only", "YON_NORMALIZATION_INGEST_ENABLED": "true",
            "COLLECTION_YOUTH_POLICY_URL": "http://notification:8000/youth",
            "COLLECTION_EMPLOYMENT24_URL": "http://notification:8000/unexpected-employment",
            "REGION_CODE_API_BASE_URL": "http://notification:8000/unexpected-region", "REGION_CODE_SYNC_ON_STARTUP": "false",
            "YON_NORMALIZATION_INGEST_POLL_INTERVAL_MS": "500", "COLLECTION_NOTIFICATION_PUBLISHER_ENABLED": "true",
            "COLLECTION_NOTIFICATION_SQS_REGION": REGION, "COLLECTION_NOTIFICATION_SQS_ENDPOINT": "http://localhost:4566",
            "COLLECTION_NOTIFICATION_SQS_QUEUE_URL": self.queue(ADMIN_QUEUE), "COLLECTION_NOTIFICATION_PUBLISHER_POLL_DELAY_MS": "500"})
        services["notification"]["environment"].update({
            "NOTIFICATION_SQS_CONSUMER_ENABLED": "true", "NOTIFICATION_SQS_REGION": REGION,
            "NOTIFICATION_SQS_ENDPOINT": "http://localhost:4566", "NOTIFICATION_SQS_QUEUE_URL": self.queue(USER_QUEUE),
            "NOTIFICATION_SQS_DLQ_URL": self.queue(USER_QUEUE + "-dlq"), "YON_NOTIFICATION_SQS_VISIBILITY_TIMEOUT_SECONDS": "60",
            "YON_NOTIFICATION_SQS_WAIT_TIME_SECONDS": "1", "NOTIFICATION_USER_API_ENABLED": "true",
            "NOTIFICATION_INTERNAL_API_KEY": "e2e-notification-only", "NOTIFICATION_PUSH_ENABLED": "false",
            "NOTIFICATION_ADMIN_SQS_CONSUMER_ENABLED": "true", "NOTIFICATION_ADMIN_SQS_REGION": REGION,
            "NOTIFICATION_ADMIN_SQS_ENDPOINT": "http://localhost:4566", "NOTIFICATION_ADMIN_SQS_QUEUE_URL": self.queue(ADMIN_QUEUE),
            "NOTIFICATION_ADMIN_SQS_DLQ_URL": self.queue(ADMIN_QUEUE + "-dlq"), "NOTIFICATION_ADMIN_SQS_WAIT_TIME_SECONDS": "1",
            "NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL": "http://localhost:8000/slack"})
        config.pop("name", None)
        config["networks"] = {"default": {}}
        self.config.write_text(json.dumps(config), encoding="utf-8")
        self.compose("up", "-d", "--wait", "db", "sqs", "backend-net", "data-net", "notification-net", "stubs")
        self.stub = "http://" + self.compose("port", "notification-net", "8000").strip()
        wait("local stub", lambda: self.ready(self.stub, "/state"))
        for name in (USER_QUEUE, ADMIN_QUEUE):
            arn = f"arn:aws:sqs:{REGION}:000000000000:{name}"
            dlq = json.loads((HERE / "dlq-attributes.json").read_text())
            dlq["RedriveAllowPolicy"] = json.dumps({"redrivePermission": "byQueue", "sourceQueueArns": [arn]})
            self.aws("create-queue", "--queue-name", name + "-dlq", "--attributes", json.dumps(dlq))
            source = json.loads((HERE / "source-attributes.json").read_text())
            source.update(VisibilityTimeout="60", RedrivePolicy=json.dumps({"deadLetterTargetArn": arn + "-dlq", "maxReceiveCount": "2"}))
            self.aws("create-queue", "--queue-name", name, "--attributes", json.dumps(source))
        # This DDL explicitly must precede Hibernate update, including on a fresh database.
        for script in ("collection-outbox.sql", "collection-outbox-per-job.sql"):
            self.sql((ROOT / "database/scripts" / script).read_text(), rows=False)
        self.compose("up", "-d", "backend")
        self.backend = "http://" + self.compose("port", "backend-net", "8080").strip()
        wait("backend ready", lambda: self.ready(self.backend, "/api/interests/options"), 120)
        for user in (1, 900001, 900002):
            self.http(self.backend, "/api/interests/options", user=user)
        date = datetime.now(timezone(timedelta(hours=9))).date().isoformat()
        self.compose("up", "-d", "data")
        wait("authenticated gRPC ready", lambda: self.ready(self.backend, "/api/admin/collection/syncs?date=" + date, 1, "ADMIN"), 120)
        self.sql((ROOT / "database/scripts/normalization-notifications.sql").read_text(), rows=False)
        self.compose("up", "-d", "notification")
        wait("notification facade ready", lambda: self.ready(self.backend, "/api/notifications", 900001), 120)

    def ready(self, base, path, user=None, role="USER"):
        try:
            self.http(base, path, user=user, role=role)
            return True
        except (AssertionError, OSError):
            return False

    def job(self, job):
        return self.http(self.backend, f"/api/admin/collection/syncs/{job}", user=1, role="ADMIN")

    def outbox(self, job):
        return self.sql(f"SELECT * FROM collection_outbox_events WHERE sync_job_id={job}")

    def receipt(self, message):
        return self.count("admin_consumed_messages", f"message_id='{message}'", "notification")

    def slack(self, job, code=200):
        return [x for x in self.state()["slack"] if x["code"] == code and f"#{job} ·" in x["body"]["text"]]

    def delivered(self, job, status, sync_type):
        result = wait("terminal job", lambda: (x if (x := self.job(job))["status"] not in ("PENDING", "RUNNING") else None))
        assert result["status"] == status, result
        event = wait("published job outbox", lambda: (x[0] if (x := self.outbox(job)) and x[0]["status"] == "PUBLISHED" else None))
        envelope = event["payload"]
        p = envelope["payload"]
        assert envelope["schemaVersion"] == 2 and envelope["eventType"] == "COLLECTION_COMPLETED"
        assert (p["jobId"], p["status"], p["syncType"]) == (job, status, sync_type), p
        start = datetime.fromisoformat(result["startedAt"])
        finish = datetime.fromisoformat(result["completedAt"])
        elapsed = finish - start
        assert p["durationMillis"] == elapsed.days * 86400000 + elapsed.seconds * 1000 + elapsed.microseconds // 1000
        assert datetime.fromisoformat(envelope["occurredAt"]) == finish
        assert p["collectedDate"] == start.astimezone(timezone(timedelta(hours=9))).date().isoformat()
        for field in ("newCount", "updatedCount", "unchangedCount", "failedPageCount"):
            assert p[field] == result[field], (field, p, result)
        wait("administrator receipt", lambda: self.receipt(envelope["messageId"]) == 1, 100)
        assert len(self.slack(job)) == 1
        wait("administrator queue ack", lambda: self.empty(ADMIN_QUEUE))
        return result, envelope

    def scenarios(self):
        path = "/api/admin/collection/syncs/YOUTH_POLICY?pageSize=100&maxPages=2"
        # The real CSRF filter runs before cookie authentication and uses the 401 entry point.
        self.http(self.backend, path, "POST", user=1, role="ADMIN", csrf=False, expected=401)
        self.http(self.backend, path, "POST", user=900001, expected=403)
        self.http(self.backend, "/api/admin/collection/syncs/1", expected=401)
        assert self.count("sync_history") == self.count("collection_outbox_events") == 0
        assert not self.state()["pages"] and not self.state()["slack"] and not self.state()["unexpected"]
        self.record("startup and authentication boundaries", {"unexpected_jobs": 0})

        first = self.http(self.backend, path, "POST", user=1, role="ADMIN", expected=202)["jobId"]
        wait("retried final page held", lambda: self.state()["pages"].get("2", 0) >= 2)
        assert self.count("raw_external_record") == 100
        assert self.job(first)["status"] == "RUNNING" and not self.outbox(first)
        assert self.count("admin_consumed_messages", role="notification") == 0 and not self.state()["slack"]
        self.control(release=True)
        result, event = self.delivered(first, "SUCCESS", "MANUAL")
        assert (result["newCount"], result["fetchedCount"], result["failedPageCount"], result["pendingCount"]) == (101, 101, 0, 0)
        assert self.state()["pages"] == {"1": 1, "2": 2}
        self.record("100 plus remainder, page retry, final completion only", {"job": first, "envelope": event})

        wait("separate normalization completion", lambda: self.count("raw_normalization_request", "status='SUCCESS'") == 101)
        assert self.count("raw_ingest_states", "outcome='NORMALIZED'") == 101
        assert self.count("opportunity_sources", "external_id LIKE 'e2e-policy-%'") == 101
        assert self.count("raw_external_record") == 101
        self.record("independent normalization result", {"requests": 101, "normalized": 101})

        self.control(mode="failed", slack_fail=True)
        failed = self.http(self.backend, path, "POST", user=1, role="ADMIN", expected=202)["jobId"]
        wait("Slack failure attempted", lambda: self.slack(failed, 503))
        pending = self.outbox(failed)[0]["payload"]
        assert self.job(failed)["status"] == "FAILED" and self.state()["pages"] == {"1": 3}
        assert self.receipt(pending["messageId"]) == 0 and not self.slack(failed)
        assert int(self.attrs(ADMIN_QUEUE)["ApproximateNumberOfMessagesNotVisible"]) == 1
        self.control(slack_fail=False)
        self.delivered(failed, "FAILED", "MANUAL")
        self.control(mode="recovery")
        retried = self.http(self.backend, f"/api/admin/collection/syncs/{failed}/retry", "POST", user=1, role="ADMIN", expected=202)["jobId"]
        recovered, recovery = self.delivered(retried, "SUCCESS", "MANUAL_RETRY")
        assert retried != failed and recovery["messageId"] != pending["messageId"]
        assert recovered["unchangedCount"] == 101 and recovered["newCount"] == 0
        assert self.job(failed)["status"] == "FAILED" and len(self.outbox(failed)) == len(self.outbox(retried)) == 1
        self.record("failure, Slack rollback/redelivery, full manual retry", {"failed_job": failed, "retried_job": retried})

        accepted = len(self.state()["slack"])
        self.send(ADMIN_QUEUE, recovery)
        marker = f"messageId={recovery['messageId']}, duplicate=true"
        wait("actual duplicate consumption", lambda: marker in self.compose("logs", "--no-color", "notification"))
        wait("duplicate acknowledged", lambda: self.empty(ADMIN_QUEUE))
        assert self.receipt(recovery["messageId"]) == 1 and len(self.state()["slack"]) == accepted
        assert self.count("notifications", role="notification") == 0
        self.record("administrator duplicate has no extra Slack/user inbox", {"messageId": recovery["messageId"]})

        self.sql((HERE / "seed.sql").read_text(), rows=False)
        message = "90000000-0000-4000-8000-000000000001"
        wait("user inbox", lambda: self.count("notifications", f"message_id='{message}'", "notification") == 1)
        owner = self.http(self.backend, "/api/notifications", user=900001)
        other = self.http(self.backend, "/api/notifications", user=900002)
        assert len(owner["items"]) == 1 and not other["items"], (owner, other)
        notification = owner["items"][0]["id"]
        self.http(self.backend, f"/api/notifications/{notification}/read", "PATCH", user=900002, expected=404)
        self.http(self.backend, f"/api/notifications/{notification}/read", "PATCH", user=900001, expected=204)
        before = self.sql(f"SELECT published_at FROM outbox_events WHERE message_id='{message}'")[0]["published_at"]
        self.sql((HERE / "replay.sql").read_text(), rows=False)
        wait("user outbox replay published", lambda: self.sql(f"SELECT status='PUBLISHED' AND published_at>'{before}'::timestamptz AS done FROM outbox_events WHERE message_id='{message}'")[0]["done"])
        wait("actual user duplicate consumption", lambda: f"messageId={message}, result=CREATED, duplicateMessage=true" in self.compose("logs", "--no-color", "notification"))
        wait("user duplicate acknowledged", lambda: self.empty(USER_QUEUE))
        assert self.count("notifications", role="notification") == self.count("consumed_messages", role="notification") == 1
        self.record("user outbox, ownership, read and duplicate", {"notification": notification})

        bad = {"schemaVersion": 999, "messageId": str(uuid.uuid4()), "eventType": "COLLECTION_COMPLETED"}
        self.send(ADMIN_QUEUE, bad)
        wait("malformed message redriven", lambda: int(self.attrs(ADMIN_QUEUE + "-dlq")["ApproximateNumberOfMessages"]) == 1, 180)
        dead = self.aws("receive-message", "--queue-url", self.queue(ADMIN_QUEUE + "-dlq"), "--visibility-timeout", "0", "--max-number-of-messages", "1")
        assert json.loads(dead["Messages"][0]["Body"]) == bad
        assert self.count("admin_consumed_messages", role="notification") == 3
        assert len(self.state()["slack"]) == accepted
        wait("poison absent from source", lambda: self.empty(ADMIN_QUEUE))
        assert not self.state()["unexpected"]
        self.record("invalid message has no receipt/Slack and reaches DLQ", {"body": bad, "maxReceiveCount": 2})

    def cleanup(self):
        if self.config.exists():
            errors = []
            try:
                for filename, capture in {
                    "containers.log": lambda: self.compose("logs", "--no-color", "--tail", "500"),
                    "stub-state.json": lambda: json.dumps(self.state(), ensure_ascii=False, indent=2),
                    "application-db.json": lambda: json.dumps({table: self.sql("SELECT * FROM " + table)
                        for table in ("sync_history", "collection_outbox_events", "outbox_events", "raw_ingest_states")}, default=str),
                    "notification-db.json": lambda: json.dumps({table: self.sql("SELECT * FROM " + table, "notification")
                        for table in ("notifications", "consumed_messages", "admin_consumed_messages")}, default=str),
                    "queues.json": lambda: json.dumps({name: self.attrs(name) for name in
                        (USER_QUEUE, USER_QUEUE + "-dlq", ADMIN_QUEUE, ADMIN_QUEUE + "-dlq")}),
                }.items():
                    try:
                        (self.output / filename).write_text(capture(), encoding="utf-8")
                    except Exception as failure:
                        errors.append({"file": filename, "error": str(failure)})
                if errors:
                    (self.output / "diagnostics-errors.json").write_text(json.dumps(errors), encoding="utf-8")
            finally:
                self.compose("down", "--volumes", "--remove-orphans", timeout=120)
            if errors and len(self.results) == 7 and all(row["status"] == "passed" for row in self.results):
                raise RuntimeError("E2E evidence capture failed: " + str(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".validation" / ("service-e2e-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")))
    parser.add_argument("--skip-build", action="store_true", help="Reuse previously built JARs (local iteration only)")
    args = parser.parse_args()
    lab = Lab(args.output)
    print(f"Evidence: {lab.output}\nOwned Docker project: {lab.project}", flush=True)
    try:
        if not args.skip_build:
            for module in ("backend", "data-service", "notification-service"):
                wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
                command = [str(ROOT / module / wrapper)] if os.name == "nt" else ["bash", str(ROOT / module / wrapper)]
                lab.command(*command, "-p", str(ROOT / module), "--no-daemon", "--console=plain", "bootJar", timeout=600)
        lab.setup()
        lab.scenarios()
        assert {row["scenario"] for row in lab.results} == SCENARIOS, "A required service E2E scenario did not execute"
    except BaseException as failure:
        lab.results.append({"scenario": "execution", "status": "failed", "error": str(failure), "traceback": traceback.format_exc()})
        lab.save()
        raise
    finally:
        lab.cleanup()
    print("PASS: 7 service E2E scenarios; owned containers removed", flush=True)


if __name__ == "__main__":
    main()
