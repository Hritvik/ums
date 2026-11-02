#!/usr/bin/env sh
# Dependency and secret audit for the release gate. The in-build SecretScanTest covers common
# leak classes; this script runs the external scanners when they are installed and fails in CI
# when they are missing, so a release cannot silently skip the gate.
set -eu
fail=0

if command -v gitleaks >/dev/null 2>&1; then
    gitleaks detect --no-git --redact --config .gitleaks.toml || fail=1
else
    echo "security-audit: gitleaks not installed" >&2
    [ "${CI:-}" = "true" ] && fail=1
fi

if command -v osv-scanner >/dev/null 2>&1; then
    osv-scanner --lockfile=gradle.lockfile || fail=1
else
    echo "security-audit: osv-scanner not installed" >&2
    [ "${CI:-}" = "true" ] && fail=1
fi

exit "$fail"
