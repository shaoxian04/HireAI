"""Standalone smoke test for the stub agent: fake dispatch in, captured callback out.

Run with the stub already listening on :9000:  python demo-agent/smoke_test.py
Exits 0 and prints PASS when the callback carries the hard-coded demo summary.
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import httpx

captured: dict = {}
done = threading.Event()


class CallbackCatcher(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        captured["body"] = json.loads(self.rfile.read(length))
        captured["auth"] = self.headers.get("Authorization", "")
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"{}")
        done.set()

    def log_message(self, *args):  # keep the test output clean
        pass


def main() -> int:
    server = HTTPServer(("127.0.0.1", 9999), CallbackCatcher)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    dispatch = {
        "taskId": "smoke-test-task",
        "callbackUrl": "http://127.0.0.1:9999/cb",
    }
    resp = httpx.post(
        "http://127.0.0.1:9000/run",
        headers={"Authorization": "Bearer smoke-token"},
        json=dispatch,
        timeout=5,
    )
    print(f"dispatch -> {resp.status_code} {resp.json()}")

    if not done.wait(timeout=10):
        print("FAIL: no callback received within 10s")
        return 1

    body = captured["body"]
    payload = json.loads(body["resultPayloadJson"])
    summary = payload.get("summary", "")
    print(f"callback auth echoed: {captured['auth'] == 'Bearer smoke-token'}")
    print(f"agentStatus: {body['agentStatus']}")
    print(f"summary starts: {summary[:80]}...")

    ok = (
        body["agentStatus"] == "COMPLETED"
        and captured["auth"] == "Bearer smoke-token"
        and "four-day work week across 61 companies" in summary
        and "turnover fell 57%" in summary
    )
    print("PASS" if ok else "FAIL: summary/status/token mismatch")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
