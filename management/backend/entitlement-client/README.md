# entitlement-client

The SDK products embed to answer entitlement questions locally. JDK `HttpClient` and Jackson
only — no Spring — so any JVM service can carry it.

## What this exists to satisfy

The whole design follows from one requirement (spec §11): *"an outage must neither take away
what a customer had nor grant what they lacked — products carry on with the last answer they saw
until the service answers again."* This module implements that posture once so that no product
embedding it has to implement it itself.

It works by keeping a local replica of the model (capabilities, plans, account→plan, overrides —
never computed answers) and running the *same* `entitlement-core` resolver the management service
runs. A decision is therefore an in-process map lookup: microseconds, and it keeps working when
the management service does not.

**The SDK answers; it does not explain.** `check()` and `checkAll()` return `(allowed, value)` and
a replica holds no trace *data* at all — reason text, authorship and timestamps deliberately never
reach it. The diagnostic `explain()` method calls the service directly and deserialises its
response; it is the only place a `Trace` appears in this module, and it fails during an outage
because there is no local answer to fall back to.

Source of truth for everything below:
[`.specs/001-entitlement-service/contracts/java-client-sdk.md`](../../../.specs/001-entitlement-service/contracts/java-client-sdk.md).

## Usage

```java
EntitlementClient client = EntitlementClient.builder()
    .serviceUrl("http://entitlements.internal:8080")
    .pollInterval(Duration.ofSeconds(5))             // default 5s — keeps answers inside the 10s bound (c29)
    .staleAfter(Duration.ofSeconds(60))               // default 60s — matches the §7 promise
    .diskCache(Path.of("/var/cache/entitlements"))    // optional; survives a restart during an outage
    .startupTimeout(Duration.ofSeconds(30))
    .startupMode(StartupMode.REQUIRE_SNAPSHOT)        // or ALLOW_DISK_CACHE
    .meterRegistry(registry)                          // optional Micrometer
    .build();                                         // blocks until the first snapshot is loaded and gated

Decision d = client.check("acct_9931", "reports.monthly");
if (d.allowed()) {
    // d.value() is Switch | Quantity(amount|unlimited) | Tier(key, ordinal) — never a trace
}

client.close();  // stops the poller; in-flight decisions complete against the snapshot they already hold
```

Construct one client per process and share it — `check`/`checkAll` are lock-free and safe from any
thread.

## Caller obligations

These bind every consumer, including a non-JVM product implementing the feed itself.

1. **Reuse an answer for no longer than 10 seconds** (c29). Do not layer a second cache in front of
   `check()` — it is already an in-memory lookup, and caching it is how the 60-second end-to-end
   promise becomes fiction. The SDK's poll interval is what keeps this true without caller effort.
2. **Do not persist decisions** into your own database as durable state. An entitlement is a
   question you ask, not a fact you own.
3. **Count usage yourself.** This service publishes the limit and never knows consumption. A
   `QUANTITY` of `0` means *none available right now* and is your job to enforce — it is not an
   error and not a denial.
4. **Do not infer *why* from `allowed`** (c18). Use `allowed` for access; for quantities compare
   your own count against `value`. For anything else, send a human to the operator UI's checker —
   that is the estate's diagnostic surface, and it is why `explain()` is diagnostic-only.
5. **Surface staleness, do not act on it.** Show `health().stale` on an internal status page and
   alert on `snapshotAge`. Never change an access decision because the replica is stale — that
   would be exactly the taking-away spec §11 forbids.
6. **Handle the three errors as errors.** `UnknownAccountException`, `UnknownCapabilityException`
   and `RetiredCapabilityException` are bugs or misconfiguration in the caller. Do not catch them
   and return "denied" (c19).
7. **One decision, one moment.** If several capabilities need to agree with each other, call
   `checkAll` once rather than `check` repeatedly — `checkAll` resolves against a single snapshot
   version (c31).
8. **Pass `minSnapshotVersion` when acting on a change you just made.** If your service wrote
   through the admin API — or was told by one that did — carry the returned `snapshotVersion` into
   the next decision. Without it, a replica up to 5 seconds behind will answer on the pre-change
   state. See "Read-your-writes across services" in the contract.
9. **Never upgrade the SDK past a `resolverContract` bump without a coordinated rollout.** A
   contract bump means the resolution rule itself changed; two consumers straddling it will
   disagree about the same account. The conformance gate turns this into a startup failure rather
   than a wrong answer, but the coordination itself is still yours.

## Errors

The SDK throws the same three distinctions the API draws, as typed exceptions — never as a denial
(c19). `check` and `checkAll` never throw for a network or service failure; that is the whole
point of embedding a replica.

| Exception | Condition |
|---|---|
| `UnknownAccountException` | The replica holds no account with that external id (after one bounded read-through confirms it with the service) |
| `UnknownCapabilityException` | No capability declared with that key |
| `RetiredCapabilityException` | The capability exists but is retired, so it is not evaluable |
| `EntitlementClientStartupException` | No snapshot loaded within `startupTimeout` and no usable disk cache, **or** the conformance gate failed |
| `SnapshotBehindException` | A `minSnapshotVersion` was supplied that the replica has not reached |
| `ExplanationUnavailableException` | `explain()` could not reach the service — diagnostic path only, never thrown by `check` |

## Behaviour under failure

The SDK never fails open and never fails closed. It has exactly one behaviour: **the last state it
knew.**

| Situation | Behaviour |
|---|---|
| Service unreachable, replica loaded | Keep answering from the replica. Do not throw. Back off 5s → 10s → 30s → 60s, jittered. |
| Service unreachable for longer than `staleAfter` | Still keep answering. `health().stale` becomes `true`; a WARN is logged once per transition, not per call; the snapshot-age gauge keeps climbing. |
| Service returns 5xx or a malformed feed | Same as unreachable. A partial or footer-less snapshot is discarded whole, never partially applied. |
| Service returns 410 `snapshot-too-old` | Discard the delta path, full-resync. The old replica keeps serving until the new snapshot is complete. |
| Service returns a `format` or `resolverContract` the SDK does not know | Stop syncing, log at ERROR, keep serving the last good replica. Fail loudly, not silently. |
| Conformance vectors fail on a newly fetched snapshot | Discard it, keep the previous snapshot, alarm. A suspect update never displaces a known-good one. |
| Caller restarts during an outage, `diskCache` configured, `startupMode ALLOW_DISK_CACHE` | Load the cached snapshot, start `stale`, keep polling. |
| Caller restarts during an outage, no disk cache | `EntitlementClientStartupException`. The SDK refuses to guess. |

## Metrics (Micrometer, when a registry is supplied)

| Metric | Type | Use |
|---|---|---|
| `entitlement.client.snapshot.version` | gauge | Convergence across replicas |
| `entitlement.client.snapshot.age` | gauge (seconds) | Alert above 60s — the §7 promise being breached |
| `entitlement.client.sync.failures` | counter | Service reachability from this caller |
| `entitlement.client.decisions` | counter, tagged `capability`, `allowed` | Which capabilities actually gate anything |
| `entitlement.client.resync.full` | counter | A replica falling behind the delta horizon |
| `entitlement.client.resolver.contract` | gauge | Alert on disagreement across replicas — a straddled rollout is visible before it is a support ticket |
| `entitlement.client.conformance.failures` | counter | The drift gate firing |
| `entitlement.client.readthrough` | counter | Unknown-account races; a sustained rise means replicas are lagging |

None of these are recorded unless `.meterRegistry(...)` is set on the builder — a product that
never calls it never loads a Micrometer class.

## Source of truth

This README is a summary for a consuming team embedding the SDK. For the complete contract —
full type signatures, the read-your-writes pattern, threading and lifecycle guarantees, and the
feed format this module replicates — see
[`contracts/java-client-sdk.md`](../../../.specs/001-entitlement-service/contracts/java-client-sdk.md)
and [`contracts/snapshot-feed.md`](../../../.specs/001-entitlement-service/contracts/snapshot-feed.md).
`entitlement-service`'s `ClientAgainstRealFeedTest` (test-scoped dependency on this module) proves
this SDK's behaviour against the real service, not a hand-written stub.
