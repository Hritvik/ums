#!/usr/bin/env python3
"""Synthetic Dutch consumer: a separate service with its own SQLite store.

It accepts only the pinned association report, stores the event id once, and exposes
the count for the journey smoke test. It is not a Dutch implementation.
"""
from __future__ import annotations

import json
import os
import sqlite3
from http.server import BaseHTTPRequestHandler, HTTPServer

DATABASE = os.environ.get("DUTCH_DB", "/data/dutch-stub.sqlite")
PORT = int(os.environ.get("PORT", "8082"))


def connect() -> sqlite3.Connection:
    connection = sqlite3.connect(DATABASE)
    connection.execute(
        "create table if not exists association_report ("
        "event_id text primary key, event_type text not null, body text not null, received_at text not null default current_timestamp)"
    )
    return connection


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self.reply(200, {"status": "UP"})
        elif self.path == "/inbox":
            with connect() as connection:
                rows = connection.execute(
                    "select event_id from association_report order by received_at").fetchall()
            self.reply(200, {"count": len(rows), "eventIds": [row[0] for row in rows]})
        else:
            self.reply(404, {"error": "unknown"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/internal/ums/association-reports":
            self.reply(404, {"error": "unknown"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 64 * 1024:
            self.reply(400, {"error": "bad length"})
            return
        raw = self.rfile.read(length)
        try:
            document = json.loads(raw)
        except json.JSONDecodeError:
            self.reply(400, {"error": "not json"})
            return
        if document.get("eventType") != "ums.profile.association-report.v1":
            self.reply(422, {"error": "unexpected event"})
            return
        event_id = document.get("eventId")
        if not isinstance(event_id, str):
            self.reply(422, {"error": "missing event id"})
            return
        with connect() as connection:
            connection.execute(
                "insert or ignore into association_report (event_id, event_type, body) values (?, ?, ?)",
                (event_id, document["eventType"], raw.decode("utf-8")))
        self.reply(202, {"accepted": True})

    def reply(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        # Path only; never the body, a subject, or a token.
        print("dutch-stub", self.command, self.path)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
