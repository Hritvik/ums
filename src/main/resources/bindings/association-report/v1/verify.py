#!/usr/bin/env python3
"""Verify the published association-report pin without network access.

Checks:
  1. every file matches the SHA-256 and size recorded in MANIFEST.json;
  2. inside the UMS repository, the published schema is byte-identical to the canonical schema;
  3. each fixture is accepted or rejected for the reason the manifest states.

This is the read-only check Dutch can run against its own copy. It validates the
contract invariants the report relies on, not every JSON Schema keyword.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
# The bundle can be copied anywhere; only compare with the canonical tree when both exist.
REPO = HERE.parents[5] if len(HERE.parents) > 5 else None
MANIFEST = HERE / "MANIFEST.json"

TOP_KEYS = {
    "schemaVersion", "eventId", "eventType", "producer", "environment", "occurredAt",
    "correlationId", "causationId", "aggregate", "destination", "payload",
}
PAYLOAD_KEYS = {
    "appSubject", "profileState", "lifecycleState", "lifecycleReason", "generation",
    "profileRevision", "retiredGeneration", "contentObligation",
}
ENVIRONMENTS = {"development", "test", "staging", "production"}
STATES = {"PROFILE_REQUIRED", "PROFILE_READY"}
LIFECYCLES = {"ACTIVE", "CLOSED", "DELETION_PENDING", "ERASED"}
REASONS = {"DELETION_REQUESTED", "DELETION_CANCELLED", "CLOSED", "ERASED", "REOPENED"}
EVENT_ID = re.compile(r"^evt_[A-Za-z0-9_-]{16,128}$")
SUBJECT = re.compile(r"^aps_[A-Za-z0-9_-]{18,128}$")
REGISTRATION = re.compile(r"^[a-z][a-z0-9-]{2,63}$")
ENVELOPE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
MAX_I64 = 9223372036854775807


TIMESTAMP = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$")
VERSION = re.compile(r"^(0|[1-9][0-9]{0,18})$")


def _timestamp(value: object) -> bool:
    if not isinstance(value, str) or not TIMESTAMP.match(value):
        return False
    try:
        dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return True


def _version(value: object) -> bool:
    return isinstance(value, str) and VERSION.match(value) is not None and int(value) <= MAX_I64


def validate_report(doc: object) -> list[str]:
    """Return rule ids the document violates; empty means the document is valid."""
    if not isinstance(doc, dict):
        return ["top-level-object"]
    bad: list[str] = []
    if set(doc) != TOP_KEYS:
        bad.append("top-level-closed")
    if doc.get("schemaVersion") != 1:
        bad.append("schema-version")
    if doc.get("eventType") != "ums.profile.association-report.v1":
        bad.append("event-type")
    if doc.get("producer") != "ums":
        bad.append("producer")
    if doc.get("environment") not in ENVIRONMENTS:
        bad.append("environment")
    if not isinstance(doc.get("eventId"), str) or not EVENT_ID.match(doc["eventId"]):
        bad.append("event-id")
    if not _timestamp(doc.get("occurredAt")):
        bad.append("occurred-at")
    if not isinstance(doc.get("correlationId"), str) or not ENVELOPE_ID.match(doc["correlationId"]):
        bad.append("correlation")
    if not isinstance(doc.get("causationId"), str) or not ENVELOPE_ID.match(doc["causationId"]):
        bad.append("causation")
    aggregate = doc.get("aggregate")
    if not isinstance(aggregate, dict) or set(aggregate) != {"type", "id", "version"} \
            or aggregate.get("type") != "UMS_PROFILE_BINDING" \
            or not isinstance(aggregate.get("id"), str) or not SUBJECT.match(aggregate["id"]) \
            or not isinstance(aggregate.get("version"), int) or aggregate["version"] < 1:
        bad.append("aggregate")
    destination = doc.get("destination")
    if not isinstance(destination, dict) or set(destination) != {"appRegistrationId"} \
            or not isinstance(destination.get("appRegistrationId"), str) \
            or not REGISTRATION.match(destination["appRegistrationId"]):
        bad.append("destination")
    payload = doc.get("payload")
    if not isinstance(payload, dict):
        return bad + ["payload-object"]
    if not set(payload).issubset(PAYLOAD_KEYS):
        bad.append("payload-closed")
    if not {"appSubject", "profileState", "lifecycleState", "contentObligation"}.issubset(payload):
        bad.append("payload-required")
    if not isinstance(payload.get("appSubject"), str) or not SUBJECT.match(payload["appSubject"]):
        bad.append("payload-subject")
    if payload.get("profileState") not in STATES:
        bad.append("payload-state")
    if payload.get("lifecycleState") not in LIFECYCLES:
        bad.append("payload-lifecycle")
    if "lifecycleReason" in payload and payload["lifecycleReason"] not in REASONS:
        bad.append("payload-reason")
    if not isinstance(payload.get("contentObligation"), bool):
        bad.append("payload-obligation")
    erased = payload.get("lifecycleState") == "ERASED"
    if erased:
        if not isinstance(payload.get("retiredGeneration"), int) or payload["retiredGeneration"] < 1:
            bad.append("tombstone-retired-generation")
        if "generation" in payload or "profileRevision" in payload:
            bad.append("tombstone-live-fields")
    else:
        if not isinstance(payload.get("generation"), int) or payload["generation"] < 1:
            bad.append("live-generation")
        if not _version(payload.get("profileRevision")):
            bad.append("live-profile-revision")
        if "retiredGeneration" in payload:
            bad.append("live-retired-generation")
    return bad


def check() -> list[str]:
    failures: list[str] = []
    manifest = json.loads(MANIFEST.read_text())
    for entry in manifest["files"]:
        path = HERE / entry["path"]
        if not path.is_file():
            failures.append(f"missing file: {entry['path']}")
            continue
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            failures.append(f"hash mismatch: {entry['path']}")
        if len(raw) != entry.get("bytes"):
            failures.append(f"size mismatch: {entry['path']}")
    schema = HERE / manifest["schema"]
    canonical = REPO / manifest["canonical"]["schema"] if REPO else None
    if canonical is not None and canonical.is_file() and schema.read_bytes() != canonical.read_bytes():
        failures.append("published schema differs from the canonical schema")
    if not (HERE / "common.json").is_file():
        failures.append("common.json is missing; the schema $ref cannot resolve")
    for fixture in manifest["fixtures"]:
        doc = json.loads((HERE / fixture["path"]).read_text())
        violations = validate_report(doc)
        if fixture["expect"] == "valid" and violations:
            failures.append(f"{fixture['path']} should be valid but broke {violations}")
        if fixture["expect"] == "reject" and fixture.get("rule") not in violations:
            failures.append(f"{fixture['path']} should break {fixture.get('rule')} but broke {violations}")
    return failures


if __name__ == "__main__":
    problems = check()
    if problems:
        for problem in problems:
            print(f"FAIL {problem}", file=sys.stderr)
        sys.exit(1)
    print("association-report pin v1: 7 files, hashes, sizes and fixture expectations match")
