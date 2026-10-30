"""Disposable public-API/Slack responses. No external network calls or credentials."""
import json
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

lock = threading.Lock()
release = threading.Event()
state = {"mode": "paged", "pages": {}, "slack_fail": False, "slack": [], "unexpected": []}


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, value):
        body = json.dumps(value, ensure_ascii=False).encode() if isinstance(value, (dict, list)) else value.encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json" if isinstance(value, (dict, list)) else "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/state":
            with lock:
                self.reply(200, state)
            return
        if url.path != "/youth":
            with lock:
                state["unexpected"].append(url.path)
            self.reply(404, "Unexpected external request")
            return
        query = parse_qs(url.query)
        page, size = int(query["pageNum"][0]), int(query["pageSize"][0])
        with lock:
            mode = state["mode"]
            attempt = state["pages"][str(page)] = state["pages"].get(str(page), 0) + 1
        if mode == "failed" or (mode == "paged" and page == 2 and attempt == 1):
            self.reply(403, "Test-only temporary refusal")
            return
        if mode == "paged" and page == 2 and not release.wait(15):
            self.reply(503, "Harness did not release the final page")
            return
        records = [
            {"plcyNo": f"e2e-policy-{i}", "plcyNm": f"E2E policy {i}",
             "sprtTrgtMinAge": "19", "sprtTrgtMaxAge": "34", "sprtTrgtAgeLmtYn": "N",
             "aplyPrdSeCd": "0057001", "aplyYmd": "20260101 ~ 20261231",
             "lclsfNm": "주거", "mclsfNm": "주거지원", "frstRegDt": "2026-01-01 10:00:00"}
            for i in range((page - 1) * size, min(page * size, 101))
        ]
        self.reply(200, {"resultCode": 200, "result": {"youthPolicyList": records,
                         "pagging": {"pageNum": page, "pageSize": size, "totCount": 101}}})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path == "/control":
            with lock:
                if "mode" in body:
                    state["mode"], state["pages"] = body["mode"], {}
                if "slack_fail" in body:
                    state["slack_fail"] = body["slack_fail"]
            if body.get("release"):
                release.set()
            self.reply(200, {"ok": True})
        elif self.path == "/slack":
            with lock:
                code = 503 if state["slack_fail"] else 200
                state["slack"].append({"body": body, "code": code,
                                       "at": datetime.now(timezone.utc).isoformat()})
            self.reply(code, "ok" if code == 200 else "Test-only Slack outage")
        else:
            self.reply(404, "Unexpected external request")

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
