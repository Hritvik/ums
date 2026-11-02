# Slice F1: outbox relay

The outbox has always recorded what happened; nothing ever sent it. This slice adds the sender:
a relay that publishes each fact at least once, in binding-revision order, with bounded retries,
a visible quarantine, and operator signals.

## Delivery model

- A pass claims only the head of each stream — the lowest unpublished `stream_revision` for one
  `(application, stream)` — so a binding can never be delivered out of order.
- Claiming holds a row lock (`for update … skip locked`), writes a fresh claim token, and leases
  the row for two minutes, so two relay instances or overlapping passes do not publish the same
  head. The claim token is re-checked on both the publish mark and the failure bookkeeping, so a
  stale instance cannot mark or quarantine a row another instance has taken over.
- The lease must exceed a full batch of connect-plus-request timeouts (validated at startup).
  It bounds the only case
  where an in-flight publish can still be overtaken: a pass that stalls for longer than the lease.
  The stale copy is then republished after newer revisions, which is a duplicate the consumer must
  discard; ordering is never violated for first deliveries.
- A fact is marked published only after the transport accepts the envelope. A crash between
  publish and mark replays the same event id, and the lease holds the retry back first. Nothing
  is marked published early, so no fact is lost.
- A failure of the local mark write is not a transport failure: it is logged, does not consume
  the delivery-attempt budget, and never aborts the rest of the batch. The row stays unpublished
  and is replayed after its lease; a mark that keeps failing exhausts the interruption budget and
  is quarantined as `RELAY_INTERRUPTED` rather than blamed on the transport.
- A failed head keeps its place and retries with exponential backoff (30 seconds doubling to one
  hour by default). Other bindings are independent and keep flowing.
- Only genuine delivery failures count as delivery attempts. Separately, a fact is counted as
  attempted when it is about to be published, so a crash while publishing one head does not spend
  the interruption budget of the rest of the batch. A fact attempted the configured number of
  times without ever recording a delivery failure is quarantined as `RELAY_INTERRUPTED`, so a
  pass that keeps dying is bounded rather than retried forever. After the configured number of failures the row is
  quarantined with `TRANSPORT_UNAVAILABLE` or `TRANSPORT_REJECTED`. The
  quarantined head holds its own `(application, stream)` — later revisions of that binding are not
  published out of order — while every other stream continues. Nothing is discarded.

## Transport

`OutboxTransport` is a single-method seam; `HttpOutboxTransport` posts the stored event envelope
unchanged to the configured consumer endpoint and discards the response body.

| Response | Class | Effect |
|---|---|---|
| 2xx | — | marked published |
| 408, 429, 5xx, timeouts, IO failure | `TRANSPORT_UNAVAILABLE` | retried with backoff |
| any other status (including 3xx) | `TRANSPORT_REJECTED` | retried, then quarantined |

When `ums.outbox.consumer-token` is set it is sent as `Authorization: Bearer …`; mutual TLS and
workload identity are deployment configuration. The response body is never read or logged.

The relay is **disabled by default**. Enabling it without a consumer endpoint fails fast at
startup rather than accumulating retries.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ums.outbox.relay.enabled` | `false` | enables the relay, scheduler, metrics and repair runner |
| `ums.outbox.consumer-uri` | — | required when enabled; HTTP(S) endpoint that accepts the envelope |
| `ums.outbox.consumer-token` | — | optional bearer token for the consumer endpoint |
| `ums.outbox.relay.interval` | `PT30S` | delay between passes |
| `ums.outbox.relay.batch-size` | `20` | maximum heads claimed per pass |
| `ums.outbox.relay.max-attempts` | `8` | attempts before quarantine |
| `ums.outbox.relay.backoff-base` | `PT30S` | first retry delay, doubled per attempt |
| `ums.outbox.relay.backoff-max` | `PT1H` | backoff ceiling |
| `ums.outbox.relay.lease` | `PT5M` | claim lease before a crashed pass can retry; must exceed 2 × batch size × timeout |
| `ums.outbox.relay.timeout-seconds` | `5` | HTTP request timeout |

## Operator signals

`OutboxRelay.status()` reports unpublished count, oldest unpublished age, retrying count,
quarantined count, oldest quarantine age, and the number of `(application, stream)` pairs whose
head is quarantined. Ages are clamped at zero so a database clock slightly ahead of the
application clock cannot produce a negative gauge. Those six values are registered as
`ums.outbox.*` gauges through one cached snapshot per five-second window. A database error
during a scrape serves the previous snapshot, and before the first snapshot the gauges report
`NaN` (unknown) rather than zero, so a cold database failure cannot read as an all-clear. No event payload,
subject, reference, or token is ever a label or a value.

## Repair

`OutboxRelay.requeue(eventId)` clears quarantine, resets the failure and claim counters, and
leaves the event id and payload untouched. The startup path is a one-shot property, so an operator can re-drive a given
fact without a code change or an internal HTTP surface:

```
UMS_OUTBOX_REPAIR_EVENT_ID=<event id>   # with the relay enabled
```

Only a quarantined row can be re-driven; the call is a no-op otherwise.

## Verification

`OutboxRelayTest` runs against real PostgreSQL and covers revision ordering, one head per pass,
backoff and its due time, quarantine after the attempt limit, isolation of a stuck stream, a
stream that only becomes due after a quarantine, re-driving with the same identity, a simulated
crash after the transport accepted (the duplicate replay), the lease hold-back after a crash, a
relay that keeps dying (bounded as `RELAY_INTERRUPTED`), due/unpublished claiming, the status and
gauge values, the snapshot window including a failed refresh, and the scheduler and repair
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
