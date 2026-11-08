#!/usr/bin/env bash
# Synthetic topology smoke for the Dutch consumer binding.
#
# Requires: bash, docker with compose v2, curl, python3, a JDK 21 for bootJar.
# It never touches a real environment, never uses a real credential, and removes
# its volumes on exit. Every check prints PASS or FAIL; the first failure stops.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../../../.." && pwd)"
COMPOSE=(docker compose -f "$DIR/docker-compose.yml" --project-name ums-dutch-binding)
export UMS_JAR
# Per-run synthetic credentials: no literal password or key lives in the repository.
export UMS_HARNESS_DB_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
export UMS_HARNESS_RECEIPT_KEY="$(python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"

pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1" >&2; exit 1; }
cleanup() { "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

# 1. The binding and the published pin must be consistent and secret-free.
python3 "$DIR/../bindings/verify.py" || fail "binding verification"
pass "binding topology, defaults and association-report pin"

# 3. Resolve the real boot jar, building it if the caller did not supply one.
if [[ -z "${UMS_JAR:-}" ]]; then
  if ! ls "$ROOT"/build/libs/ums-*.jar >/dev/null 2>&1; then
    (cd "$ROOT" && ./gradlew bootJar --console=plain >/dev/null) || fail "bootJar"
  fi
  UMS_JAR="$(ls "$ROOT"/build/libs/ums-*.jar | grep -v -- '-plain' | head -1)"
  export UMS_JAR
fi
[[ -f "$UMS_JAR" ]] || fail "UMS artifact missing at $UMS_JAR"
UMS_JAR="$(cd "$(dirname "$UMS_JAR")" && pwd)/$(basename "$UMS_JAR")"
export UMS_JAR
pass "UMS artifact present"

# 4. Compose needs the artifact path, so validate after it is resolved.
"${COMPOSE[@]}" config --quiet || fail "compose configuration"
pass "compose configuration resolves the artifact"

# 5. Stand the topology up.
"${COMPOSE[@]}" up -d || fail "compose up"
for _ in $(seq 1 90); do
  if curl -fsS "http://localhost:18080/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "http://localhost:18081/health" >/dev/null || fail "IAM stub health"
curl -fsS "http://localhost:18080/actuator/health" >/dev/null || {
  "${COMPOSE[@]}" logs --no-color ums | tail -40 >&2
  fail "UMS health"
}
pass "UMS and IAM stub health endpoints are UP"

# 6. The authenticated surface stays closed without a caller token.
status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:18080/api/v1/profile")"
[[ "$status" == "401" ]] || fail "unauthenticated /api/v1/profile returned $status"
pass "unauthenticated profile route is 401"

# 7. Privacy-sensitive capabilities are off in the shipped profile.
relay="$( "${COMPOSE[@]}" exec -T ums printenv UMS_OUTBOX_RELAY_ENABLED )"
content="$( "${COMPOSE[@]}" exec -T ums printenv UMS_CONTENT_AUTHORITY_URI || true )"
[[ "$relay" == "false" ]] || fail "relay default is $relay"
[[ -z "$content" ]] || fail "content authority default is not empty"
pass "outbox relay and content authority are default-off"

# 8. The UMS store migrated to the published version on its own database.
version="$( "${COMPOSE[@]}" exec -T ums-db psql -U ums -d ums -tAc \
  "select max(version::int) from flyway_schema_history" )"
[[ "$version" == "25" ]] || fail "UMS schema version is $version"
pass "UMS store is at migration 25"

# 9. With egress disabled the separate Dutch store stays empty.
count="$(curl -fsS "http://localhost:18082/inbox" | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])')"
[[ "$count" == "0" ]] || fail "Dutch stub received $count reports while egress is off"
pass "Dutch store is separate and empty while egress is off"

# 10. Fail closed: enabling egress without a consumer URI must stop startup.
if "${COMPOSE[@]}" run --rm --no-deps -e UMS_OUTBOX_RELAY_ENABLED=true ums \
    >"$DIR/.negative.log" 2>&1; then
  fail "UMS started with egress enabled and no consumer URI"
fi
grep -qE 'DUTCH_UMS_REPORT_URI|consumer-uri' "$DIR/.negative.log" \
  || fail "startup failed for a reason other than the missing consumer URI"
pass "egress without a consumer URI fails startup for that reason"
rm -f "$DIR/.negative.log"

printf '\nAll synthetic checks passed. No real service or credential was used.\n'
