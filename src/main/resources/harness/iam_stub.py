#!/usr/bin/env python3
"""Synthetic IAM producer endpoint for the topology.

The real authenticated ingress is IAM DEP-2 work. This stub only proves the UMS
binding names a producer it can reach; it never authenticates anyone.
"""
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("PORT", "8081"))
received: list[str] = []


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self.reply(200, {"status": "UP"})
        elif self.path == "/inbox":
            self.reply(200, {"count": len(received)})
        else:
            self.reply(404, {"error": "unknown"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/internal/v1/iam/events":
            self.reply(404, {"error": "unknown"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 32 * 1024:
            self.reply(400, {"error": "bad length"})
            return
        raw = self.rfile.read(length)
        try:
            event_id = json.loads(raw).get("eventId")
        except json.JSONDecodeError:
            self.reply(400, {"error": "not json"})
            return
        if not isinstance(event_id, str):
            self.reply(422, {"error": "missing event id"})
            return
        received.append(event_id)
        self.reply(202, {"accepted": True})

    def reply(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        print("iam-stub", self.command, self.path)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
