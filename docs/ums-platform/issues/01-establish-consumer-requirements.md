Type: grilling
Status: resolved
Assignee: Codex
Parent: [Specify the shared User Management Service](../map.md)

# Establish consumer requirements and adoption boundaries

## Question

Which concrete outcomes do Dutch, Midas, Jarvis, Chat, Health Tracker, and the portfolio hub require from shared UMS, and which are required now, optional, or unsupported by evidence? Trace each expectation to a consumer source, preserve its acceptance scenario, distinguish proposals from commitments, and identify necessary changes to historical Dutch-specific boundaries without changing those products' decisions on their behalf.

## Starting evidence

Dutch explicitly requires idempotent profile creation/readiness and private replay-safe member mapping. IAM supplies app-scoped onboarding/lifecycle facts. Existing Midas/Jarvis plans exclude Dutch UMS; other products have not yet accepted UMS commitments.

## Working findings

The central capability contract `app-isolated-profile-management` records the source references, adoption assessment, and unresolved owner actions. It lives in the portfolio capability home, not this repository. Consumer adoption and provider promises are not inferred from generic profile needs.

Confirmed user direction makes adoption optional and keeps Dutch claims and product-domain data product-owned. Existing Dutch consumer expectations remain unchanged at source. IAM's Dutch-specific ownership wording must be reconciled; this session does not silently edit IAM or product decisions.

## Answer

The initial consumer inventory is complete: Dutch supplies explicit required profile/readiness and replay-safe mapping outcomes; Midas/Jarvis explicitly have no accepted UMS dependency; Chat, Health Tracker, and a future portfolio hub are candidates without accepted shared-profile expectations. Optional adoption follows the user's confirmed direction. No new consumer acceptance or provider promise is inferred.

The central contract `app-isolated-profile-management` is exploring, with source references, product constraints, and explicit ownership gaps. Existing Dutch requirements retain their IDs and authorship at source. This resolution establishes the baseline inventory, not a claim that future consumer discovery is complete.

Midas financial timezone and Jarvis travel-aware time are different product semantics. UMS locale/timezone fields cannot become authority over those domains. Jarvis personality presets called profiles likewise remain Jarvis-owned. Household membership/consent and health records remain Health Tracker-owned; Chat participation and permissions remain Chat-owned.

Follow-up design must define the Dutch Member/shared-profile seam and reconcile IAM's historical Dutch-owned consumer assumption. Non-Dutch adoption needs a concrete consumer-authored outcome before a commitment can be offered. No implementation or runtime validation has occurred.
