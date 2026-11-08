#!/usr/bin/env python3
"""Validate the secret-free Dutch consumer binding and the published report pin.

Run from any directory: python3 src/main/resources/bindings/verify.py

This deliberately has no network or third-party dependency, so Dutch can run the
same check against the published bundle. It fails on a literal secret, an
unpinned service or store, an unsafe default capability, or a broken pin.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
BINDING = HERE / "dutch-consumer-binding.json"
PROFILE = HERE / "application-dutch.yaml"
PIN = HERE / "association-report/v1"

UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
REGISTRATION = re.compile(r"^[a-z][a-z0-9-]{2,63}$")
SUBJECT = re.compile(r"^aps_[A-Za-z0-9_-]{18,128}$")
ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")
PATH = re.compile(r"^/internal/[a-z0-9/_-]{1,120}$")
DURATION = re.compile(r"^PT([0-9]{1,6})([SMH])$")


def seconds(value: str) -> int:
    match = DURATION.match(value)
    if not match:
        return -1
    scale = {"S": 1, "M": 60, "H": 3600}[match.group(2)]
    return int(match.group(1)) * scale
SECRET_KEYS = {"token", "password", "secret", "clientSecret", "apiKey", "bearerToken", "privateKey"}


def fail(problems: list[str], condition: bool, message: str) -> None:
    if not condition:
        problems.append(message)


def walk_secrets(value: object, path: str, problems: list[str]) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            fail(problems, key not in SECRET_KEYS, f"secret-shaped key at {path}.{key}")
            walk_secrets(item, f"{path}.{key}", problems)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            walk_secrets(item, f"{path}[{index}]", problems)


def check_binding(doc: dict) -> list[str]:
    problems: list[str] = []
    fail(problems, doc.get("apiVersion") == "ums.vik/dutch-binding-v1", "unexpected apiVersion")
    fail(problems, doc.get("binding") == "dutch-consumer", "unexpected binding name")
    fail(problems, doc.get("environment") in {"development", "test", "staging", "production"},
         "environment is not a published value")
    walk_secrets(doc, "$", problems)

    services = doc.get("services") or {}
    fail(problems, set(services) >= {"ums", "iam-stub", "dutch-stub"}, "missing a peer service")
    ports = []
    for name, service in services.items():
        image = str(service.get("image", ""))
        fail(problems, bool(image) and not image.endswith(":latest"),
             f"{name} image is not pinned to a version or digest")
        ports.append(service.get("port"))
    fail(problems, len(set(ports)) == len(ports), "services share a port")

    stores = doc.get("stores") or {}
    ums_store = stores.get("ums") or {}
    dutch_store = stores.get("dutch-stub") or {}
    fail(problems, ums_store.get("engine") == "postgresql", "UMS store is not PostgreSQL")
    fail(problems, re.fullmatch(r"17\.[0-9]+", str(ums_store.get("version", ""))) is not None,
         "UMS store version is not pinned to 17.x")
    fail(problems, ums_store.get("migrations") == 25, "UMS store does not record migration 25")
    fail(problems, dutch_store.get("engine") in {"postgresql", "sqlite"}, "Dutch store engine is not supported")
    fail(problems, re.fullmatch(r"[0-9]+(\.[0-9]+)?", str(dutch_store.get("version", ""))) is not None,
         "Dutch store version is not pinned")
    fail(problems, dutch_store.get("separate") is True, "Dutch store is not separate")
    fail(problems, ums_store.get("database") != dutch_store.get("database"), "stores share a database")

    trust = doc.get("trust") or {}
    for key in ("issuer", "jwkSetUri"):
        fail(problems, str(trust.get(key, "")).startswith("https://"), f"trust.{key} is not HTTPS")
    fail(problems, bool(trust.get("audience")), "trust.audience is empty")
    fail(problems, UUID.match(str(trust.get("issuerEnvironmentRef", ""))) is not None,
         "trust.issuerEnvironmentRef is not a UUID")
    fail(problems, ENV_NAME.match(str(trust.get("callerRegistrations", ""))) is not None,
         "caller registrations must be an environment variable name, not a value")

    app = doc.get("appScope") or {}
    fail(problems, REGISTRATION.match(str(app.get("registration", ""))) is not None,
         "appScope.registration is malformed")
    fail(problems, UUID.match(str(app.get("appReference", ""))) is not None,
         "appScope.appReference is not a UUID")
    fail(problems, SUBJECT.match(str(app.get("appSubject", ""))) is not None,
         "appScope.appSubject is malformed")

    ingress = doc.get("ingress") or {}
    fail(problems, ingress.get("eventTypes") == ["iam.app-subject.onboarding-ready.v1"],
         "ingress event type is not the pinned onboarding event")
    fail(problems, ingress.get("producer") == "iam", "ingress producer is not iam")
    fail(problems, ingress.get("transport") in {"workload-mtls", "workload-bearer"},
         "ingress transport is not a workload identity mode")
    fail(problems, PATH.match(str(ingress.get("path", ""))) is not None, "ingress path is not internal")
    fail(problems, isinstance(ingress.get("maxBodyBytes"), int)
         and 1024 <= ingress["maxBodyBytes"] <= 1024 * 1024, "ingress body bound is out of range")

    snapshot = doc.get("snapshotRepair") or {}
    fail(problems, snapshot.get("transport") in {"workload-mtls", "workload-bearer"},
         "snapshot transport is not a workload identity mode")
    fail(problems, PATH.match(str(snapshot.get("path", ""))) is not None,
         "snapshot path is not internal")
    fail(problems, isinstance(snapshot.get("pageSize"), int)
         and 1 <= snapshot["pageSize"] <= 1000, "snapshot page size is out of range")
    fail(problems, isinstance(snapshot.get("maxPages"), int)
         and 1 <= snapshot["maxPages"] <= 100, "snapshot page bound is out of range")

    egress = doc.get("egress") or {}
    fail(problems, egress.get("eventType") == "ums.profile.association-report.v1",
         "egress event type is not the pinned association report")
    fail(problems, str(egress.get("consumerUri", "")).startswith("https://"),
         "egress consumer is not HTTPS")
    fail(problems, egress.get("auth") in {"workload-mtls", "workload-bearer"},
         "egress auth is not a workload identity mode")
    fail(problems, bool(egress.get("correlationHeader")), "egress correlation header is empty")
    fail(problems, egress.get("dedupe") == "eventId", "egress dedupe key is not the event id")
    retry = egress.get("retry") or {}
    fail(problems, isinstance(retry.get("maxAttempts"), int)
         and 1 <= retry["maxAttempts"] <= 100, "retry budget is out of range")
    for key in ("backoffBase", "backoffMax", "lease"):
        fail(problems, DURATION.match(str(retry.get(key, ""))) is not None, f"retry.{key} is not a duration")
    fail(problems, 0 < seconds(str(retry.get("backoffBase", "")))
         < seconds(str(retry.get("backoffMax", ""))), "retry backoff does not grow")

    correlation = doc.get("correlation") or {}
    fail(problems, correlation.get("pattern") == "^[A-Za-z0-9._:-]{1,128}$",
         "correlation pattern differs from the UMS contract")
    fail(problems, correlation.get("causation") == "source-event-id", "causation is not the source event")

    capabilities = doc.get("capabilities") or {}
    fail(problems, set(capabilities) == {"privacyMode", "notices", "contentAuthority", "outboxRelay"},
         "capability set is incomplete")
    for name, enabled in capabilities.items():
        fail(problems, enabled is False, f"privacy-sensitive capability {name} is not default-off")

    pin = doc.get("pin") or {}
    for key in ("schema", "manifest"):
        fail(problems, (HERE / str(pin.get(key, ""))).is_file(), f"pin.{key} does not exist")
    return problems


def check_profile(path: Path) -> list[str]:
    problems: list[str] = []
    if not path.is_file():
        return [f"missing {path.name}"]
    for number, line in enumerate(path.read_text().splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("#") or ":" not in stripped:
            continue
        key, _, value = stripped.partition(":")
        if key.strip() in SECRET_KEYS and value.strip() and not value.strip().startswith("${"):
            problems.append(f"{path.name}:{number} has a literal {key.strip()}")
    return problems


def check_pin() -> list[str]:
    sys.path.insert(0, str(PIN))
    try:
        import verify  # type: ignore[import-not-found]
    finally:
        sys.path.pop(0)
    return verify.check()


if __name__ == "__main__":
    document = json.loads(BINDING.read_text())
    failures = check_binding(document) + check_profile(PROFILE) + check_pin()
    if failures:
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        sys.exit(1)
    print("dutch-consumer binding v1: topology, defaults, secret scan and report pin all pass")
