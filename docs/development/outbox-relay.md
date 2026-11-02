# Slice F1: outbox relay

The outbox has always recorded what happened; nothing ever sent it. This slice adds the sender:
a relay that publishes each fact at least once, in binding-revision order, with bounded retries,
a visible quarantine, and operator signals.

## Delivery model

- A pass claims only the head of each stream — the lowest unpublished `stream_revision` for one
  `(application, stream)` — so a binding can never be delivered out of order.
- Claiming holds a row lock (`for update … skip locked`) and leases the row for two minutes, so
  two relay instances or two overlapping passes do not publish the same head simultaneously.
- A fact is marked published only after the transport accepts the envelope. A crash between
  publish and mark replays the same event id: the fact is delivered twice and the consumer
  discards the duplicate. Nothing is marked published early, so no fact is lost.
- A failed head keeps its place and retries with exponential backoff (30 seconds doubling to one
  hour by default). Other bindings are independent and keep flowing.
- After the configured number of attempts the row is quarantined with a safe failure class
  (`TRANSPORT_UNAVAILABLE` or `TRANSPORT_REJECTED`). The quarantined head holds its own stream —
  later revisions of that binding are not published out of order — while every other stream
  continues. Nothing is discarded.

## Transport

`OutboxTransport` is a single-method seam; `HttpOutboxTransport` posts the stored event envelope
unchanged to the configured consumer endpoint and discards the response body.

| Response | Class | Effect |
|---|---|---|
| 2xx | — | marked published |
| 408, 429, 5xx, timeouts, IO failure | `TRANSPORT_UNAVAILABLE` | retried with backoff |
| any other status | `TRANSPORT_REJECTED` | retried, then quarantined |

The relay is **disabled by default**. Enabling it without a consumer endpoint fails fast at
startup rather than accumulating retries.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ums.outbox.relay.enabled` | `false` | enables the relay, scheduler, metrics and repair runner |
| `ums.outbox.consumer-uri` | — | required when enabled; HTTP(S) endpoint that accepts the envelope |
| `ums.outbox.relay.interval` | `PT30S` | delay between passes |
| `ums.outbox.relay.batch-size` | `20` | maximum heads claimed per pass |
| `ums.outbox.relay.max-attempts` | `8` | attempts before quarantine |
| `ums.outbox.relay.backoff-base` | `PT30S` | first retry delay, doubled per attempt |
| `ums.outbox.relay.backoff-max` | `PT1H` | backoff ceiling |
| `ums.outbox.relay.lease` | `PT2M` | claim lease before a crashed pass can retry |
| `ums.outbox.relay.timeout-seconds` | `5` | HTTP request timeout |

## Operator signals

`OutboxRelay.status()` reports unpublished count, oldest unpublished age, retrying count,
quarantined count, oldest quarantine age, and the number of streams whose head is quarantined.
Those six values are registered as `ums.outbox.*` gauges, so they appear wherever Micrometer
metrics are scraped. No event payload, subject, reference, or token is ever a label or a value.

## Repair

`OutboxRelay.requeue(eventId)` clears quarantine, resets attempts, and leaves the event id and
payload untouched. The startup path is a one-shot property, so an operator can re-drive a given
fact without a code change or an internal HTTP surface:

```
UMS_OUTBOX_REPAIR_EVENT_ID=<event id>   # with the relay enabled
```

Only a quarantined row can be re-driven; the call is a no-op otherwise.

## Verification

`OutboxRelayTest` runs against real PostgreSQL and covers revision ordering, one head per pass,
backoff and its due time, quarantine after the attempt limit, isolation of a stuck stream,
re-driving with the same identity, a simulated crash after the transport accepted (the duplicate
replay), due/unpublished claiming, the status and gauge values, and the scheduler and repair
runner. `HttpOutboxTransport` is tested against a real local HTTP server for acceptance, a
retryable status, a refused status, and an unreachable endpoint.

## Deliberate limits

- The consumer endpoint is a single URI; per-application routing is deployment configuration
  until a consumer runtime contract exists.
- Publication means the transport accepted the envelope, not that the consumer applied it.
- Quarantine is visible and repairable, not automatically re-driven; a poison fact needs a
  decision, not a loop.
- `skip locked` makes concurrent instances safe, but no instance-coordination or failover is
  claimed beyond that.
