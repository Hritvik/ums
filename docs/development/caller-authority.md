# Slice C1: production caller authority

The caller resolver is implemented and unit-verified against the pinned IAM provider
fixture. Business HTTP routes remain denied; this is the authority seam those routes
will use.

## Contract, not invention

UMS validates the provider's published `human_access` v1 token claims rather than
locally invented names. The profile, version, subject kind, and claim names come
from the pinned IAM 1.0.0 archive (`oauth/human-access-token-claims.schema.json`),
and the semantic rules come from its conformance requirements.

| Header or claim | UMS use |
|---|---|
| `typ`, `alg`, `kid` | JOSE header must be `at+jwt`, `ES256`, and a bounded key id |
| `iss` | must equal the configured issuer |
| `aud` | must equal the caller's single registered resource audience |
| `exp`, `iat` | expiry after issue and in the future |
| `auth_time` | may be absent; must not be after `iat` |
| `jti` | required, bounded; available for later replay controls |
| `sub` | app subject reference (`aps_…`), never a global identity |
| `app_id` | app registration; must match a registered app |
| `client_id` | registered client; must match that registration |
| `scope` | space-separated; every scope must be in the registration's allowed set |
| `token_profile`, `token_version`, `subject_kind` | must be `human_access`, `1`, `app_subject` |
| `security_version` | positive; available for later stale-authority policy |
| `acr` | mapped to assurance: `aal2`/`aal3` strong, `aal1` single, absent none, blank or unknown denied |
| `amr` | validated (1–8 unique bounded methods) and carried for audit, not authorization |
| `cnf.jkt` | required, closed object; see the DPoP limit below |

The tuple rules (issuer, app, client, audience, scope subset, confirmation key,
and timestamp ordering) are the provider's MUST requirements. The published claim
set is enforced exactly, so an undeclared claim is rejected rather than ignored.
An unknown app/client pair is denied without revealing which side is wrong.

## Deliberate limits

- **DPoP proof verification is not implemented.** The resolver requires a
  well-formed `cnf.jkt` but cannot verify a proof without the request binding
  key; that belongs with the ingress slice. A registration that requires DPoP is
  currently only checked for the thumbprint's presence and shape.
- **Workload and delegation tokens are denied.** Only `human_access` v1 is
  published; guessing a workload profile would invent a provider contract.
  Registered one-hop delegation remains an IAM decision.
- **No registration source is wired.** The resolver takes a
  `CallerRegistrationLookup`; loading reviewed registrations from configuration
  or the policy registry belongs to the HTTP-surface slice.
- **No route uses the resolver yet.** The security chain still denies every
  business request, so this slice cannot be exercised over HTTP.
- Security-version staleness is carried, not enforced: the current-version policy
  needs an IAM contract for how a resource learns the current version.

## Verification

At this commit, `./gradlew clean check bootJar --no-daemon --console=plain` passed
with 161 JVM tests, zero failures/skips, 7 contract tests, 22 producer checks, and
97.8% line / 80.3% branch coverage.

`IamCallerResolverTest` loads the pinned provider fixtures from
`contracts/dependencies/iam/iam-contracts-1.0.0.zip`: the accepted token and
header, six of the published rejected tokens (issuer, audience, app, client,
scope, undeclared field), and a decoder-shaped token where `aud` arrives as a
one-element list and timestamps are normalized. The schema publishes a single
audience string, but the decoder materialises a list, so both shapes are accepted
and only exactly one value is allowed. It also asserts the remaining denials — JOSE header,
expiry, future `auth_time`, profile, version, subject kind, malformed references
and numbers, unregistered caller, scope escalation, confirmation key, malformed
authentication methods, and unknown authentication context — and that assurance is
derived from `acr` rather than assumed.

One provider fixture cannot be exercised: `exp` equal to `iat` is refused by the
`Jwt` type itself, so the resolver keeps the defensive ordering check for tokens
produced elsewhere rather than testing an unrepresentable state.

RED evidence: the first version of this slice invented configurable claim names
and the tests were hand-built. Reading the pinned archive showed the provider
already publishes the exact claim contract, so the design was replaced with
contract-driven validation and a fixture-based conformance test.

## Remaining gates

IAM must confirm the UMS resource audience and registration tuple, publish a
workload/delegation profile if products need one, and define how a resource learns
current security versions. The ingress slice must verify DPoP proofs, load
registrations, and map app-scoped references to UMS scope identifiers.
