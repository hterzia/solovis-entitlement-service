# Contract — Java Client SDK (`entitlement-client`)

**Artifact**: `com.solovis:entitlement-client` | **Depends on**: `com.solovis:entitlement-core`, Jackson. No Spring, JDK `HttpClient` only, so any JVM service can embed it.

This is the mechanism spec §11 leaves to engineering: *"an outage must neither take away what a customer had nor grant what they lacked — products carry on with the last answer they saw until the service answers again."* The SDK implements that posture once so that no product has to implement it at all.

It works by keeping a local replica of the model ([`snapshot-feed.md`](./snapshot-feed.md)) and running the same `entitlement-core` resolver the service runs. Decisions are in-process map lookups, so they are microseconds and they keep working when the management service does not.

**The SDK answers; it does not explain.** Only the operator UI needs the §6.1 trace, so `check()` and `checkAll()` return `(allowed, value)` and a replica holds no trace **data** at all — reason text, authorship and timestamps deliberately never reach it. The `Trace` type itself lives in `entitlement-core` and appears in this client only on the diagnostic `explain()` path, which deserialises the service's response; the local decision path neither builds nor holds one. Explanations come from the service, which holds the complete record. See `plan.md`, "Recorded interpretations", for how this reads against criterion 21.

---

## Public API surface

### `EntitlementClient`

```java
public interface EntitlementClient extends AutoCloseable {

    /** One capability. Local, lock-free, microseconds. Never throws on service failure. */
    Decision check(String accountExternalId, String capabilityKey);

    /** Every non-retired capability for one account, resolved at one snapshot version (c31). */
    AccountEntitlements checkAll(String accountExternalId);

    /** As check(), but resolved at or above minSnapshotVersion — see "Read-your-writes". */
    Decision check(String accountExternalId, String capabilityKey, long minSnapshotVersion);

    /** DIAGNOSTIC ONLY. Always calls the service; never resolves locally.
     *  Not for a request path: it is a network call and it fails during an outage. */
    Explanation explain(String accountExternalId, String capabilityKey);

    /** The capability registry, including tier orders, for interpreting values. */
    Optional<Capability> capability(String capabilityKey);
    List<Capability> capabilities();

    /** Replica freshness. Callers should surface this, not branch on it for access decisions. */
    ClientHealth health();

    /** Opt-in: block until the replica reaches a version, or time out. Not a default. */
    boolean awaitVersion(long snapshotVersion, Duration timeout);

    @Override void close();
}
```

### `Decision` (from `entitlement-core`)

```java
public record Decision(
    String accountExternalId,
    String capabilityKey,
    boolean allowed,               // §5 — holds the capability, nothing more (c18)
    EntitlementValue value,        // Switch | Quantity(amount|unlimited) | Tier(key, ordinal)
    long snapshotVersion,
    Instant evaluatedAt
) {}
```

No `trace` field. `checkAll` returns `AccountEntitlements(account, planKey, List<Decision>, snapshotVersion, evaluatedAt)` — the same element type, so the two methods have one shape between them.

### `Explanation` (service-fetched)

```java
public record Explanation(Decision decision, Trace trace) {}
```

Produced by `Resolver.explain()` in the management service and deserialised here from the JSON in [`decision-api.md`](./decision-api.md). A replica cannot construct one: it lacks the reason text, authorship and timestamps a trace names. That is the point — one component holds the record, so one component produces the explanation *(c24)*.

### `ClientHealth`

```java
public record ClientHealth(
    long snapshotVersion,
    Instant snapshotPublishedAt,
    Duration snapshotAge,
    boolean stale,                 // true once a sync has been failing longer than staleAfter
    Instant lastSuccessfulSync,
    Optional<String> lastError
) {}
```

### Builder

```java
EntitlementClient client = EntitlementClient.builder()
    .serviceUrl("http://entitlements.internal:8080")
    .pollInterval(Duration.ofSeconds(5))       // default 5s — keeps answers inside the 10s bound (c29)
    .staleAfter(Duration.ofSeconds(60))        // default 60s — matches the §7 promise
    .diskCache(Path.of("/var/cache/entitlements"))  // optional; survives a restart during an outage
    .startupTimeout(Duration.ofSeconds(30))
    .startupMode(StartupMode.REQUIRE_SNAPSHOT)  // or ALLOW_DISK_CACHE
    .meterRegistry(registry)                    // optional Micrometer
    .build();                                   // blocks until the first snapshot is loaded
```

---

## Errors

The SDK throws the same three distinctions the API draws, as typed exceptions — never as a denial *(c19)*.

| Exception | Condition |
|---|---|
| `UnknownAccountException` | The replica holds no account with that external id |
| `UnknownCapabilityException` | No capability declared with that key |
| `RetiredCapabilityException` | The capability exists but is retired, so it is not evaluable |
| `EntitlementClientStartupException` | No snapshot loaded within `startupTimeout` and no usable disk cache, **or** the conformance gate failed |
| `SnapshotBehindException` | A `minSnapshotVersion` was supplied that the replica has not reached |
| `ExplanationUnavailableException` | `explain()` could not reach the service. Diagnostic path only — never thrown by `check` |

`check` and `checkAll` **never** throw for network or service failure. That is the whole point: once a replica exists, the service's availability stops being on the caller's critical path.

### Unknown account — the ambiguity, and what the SDK does about it

With a replica, "unknown account" can mean *the account does not exist* or *it was created three seconds ago and this replica has not caught up*. Throwing on the second case would fail at signup, the worst possible moment. So before raising `UnknownAccountException` the SDK makes **one bounded read-through call** to the service and triggers an out-of-band poll. If the service confirms the account, the answer is served and the replica converges moments later.

If the service is unreachable, it throws. Inventing entitlements for an account it has never seen would be exactly the *granting* §11 forbids, and there is no last answer to carry on with. The exception carries `snapshotAge` and `readThroughAttempted` so a caller can tell a genuine 404 from an outage-plus-race.

### Conformance gate

`build()` loads the feed's conformance vectors and evaluates them with this SDK's own engine. Any mismatch, or a `resolverContract` this version does not implement, fails construction with `EntitlementClientStartupException`. Mid-life, a failing gate on a newly fetched snapshot means the **previous** snapshot keeps serving and an alarm is raised — a suspect update never displaces a known-good one.

This is the primary defence against two replicas on different SDK versions answering differently for the same account. It has to be proactive: with no local traces, a wrong answer leaves nothing to diagnose after the fact. See `research.md` §20.

---

## Behaviour under failure — the §11 posture, precisely

| Situation | Behaviour |
|---|---|
| Service unreachable, replica loaded | Keep answering from the replica. Do not throw. Back off 5 s → 10 s → 30 s → 60 s, jittered. |
| Service unreachable for longer than `staleAfter` | Still keep answering. `health().stale` becomes `true`; a WARN is logged once per transition, not per call; the `entitlement.client.snapshot.age` gauge keeps climbing. |
| Service returns 5xx or malformed feed | Same as unreachable. A partial or footer-less snapshot is discarded whole, never partially applied. |
| Service returns 410 `snapshot-too-old` | Discard the delta path, full-resync. The old replica keeps serving until the new snapshot is complete. |
| Service returns a `format` or `resolverContract` the SDK does not know | Stop syncing, log at ERROR, keep serving the last good replica. Fail loudly, not silently. |
| Conformance vectors fail on a newly fetched snapshot | Discard it, keep the previous snapshot, alarm. A suspect update never displaces a known-good one. |
| Caller restarts during an outage, `diskCache` configured, `startupMode ALLOW_DISK_CACHE` | Load the cached snapshot, start `stale`, keep polling. The customer's entitlements survive the restart. |
| Caller restarts during an outage, no disk cache | `EntitlementClientStartupException`. The SDK refuses to guess — there is no last answer to carry on with, and inventing one would either take away or grant. |

The SDK never fails open and never fails closed. It has exactly one behaviour: **the last state it knew**.

---

## Caller obligations

These bind every consumer, including non-JVM products implementing the feed themselves.

1. **Reuse the SDK's answer for no longer than 10 seconds** *(c29)*. Do not layer a second cache in front of `check()` — it is already an in-memory lookup, and caching it is how the 60-second end-to-end promise becomes fiction. The SDK's 5-second poll is what keeps this true without any caller effort.
2. **Do not persist decisions** into your own database as durable state. An entitlement is a question you ask, not a fact you own.
3. **Count usage yourself.** This service publishes the limit and never knows consumption (§2). A `QUANTITY` of `0` means *none available right now* and is your job to enforce — it is not an error and not a denial (§5).
4. **Do not infer *why* from `allowed`** *(c18)*. Use `allowed` for access; for quantities compare your own count against `value`. For anything else, send a human to the operator UI's checker — that is the estate's diagnostic surface, and it is why `explain()` is marked diagnostic-only.
5. **Surface staleness, do not act on it.** Show `health().stale` on an internal status page and alert on `snapshotAge`. Never change an access decision because the replica is stale — that would be exactly the taking-away §11 forbids.
6. **Handle the three errors as errors.** `UnknownAccountException`, `UnknownCapabilityException` and `RetiredCapabilityException` are bugs or misconfiguration in the caller. Do not catch them and return "denied" *(c19)*.
7. **One decision, one moment.** If you need several capabilities consistently, call `checkAll` once rather than `check` repeatedly — `checkAll` resolves against a single snapshot version *(c31)*.
8. **Pass `minSnapshotVersion` when you are acting on a change you just made.** If your service wrote through the admin API — or was told by one that did — carry the returned `snapshotVersion` into the next decision. Without it, a replica up to 5 seconds behind will answer on the pre-change state, which lands on the customer immediately after an upgrade. See "Read-your-writes" below.
9. **Never upgrade the SDK past a `resolverContract` bump without a coordinated rollout.** A contract bump means the rule itself changed; two consumers straddling it will disagree about the same account. The gate turns this into a startup failure rather than a wrong answer, but the coordination is still yours.

## Metrics emitted (Micrometer, when a registry is supplied)

| Metric | Type | Use |
|---|---|---|
| `entitlement.client.snapshot.version` | gauge | Convergence across replicas |
| `entitlement.client.snapshot.age` | gauge (seconds) | **Alert above 60 s** — the §7 promise being breached |
| `entitlement.client.sync.failures` | counter | Service reachability from this caller |
| `entitlement.client.decisions` | counter, tagged `capability`, `allowed` | Which capabilities actually gate anything |
| `entitlement.client.resync.full` | counter | A replica falling behind the delta horizon |
| `entitlement.client.resolver.contract` | gauge | **Alert on disagreement across replicas** — a straddled rollout is visible before it is a support ticket |
| `entitlement.client.conformance.failures` | counter | The drift gate firing |
| `entitlement.client.readthrough` | counter | Unknown-account races; a sustained rise means replicas are lagging |

---

## Read-your-writes across services

Criterion 30 guarantees an operator sees their own change immediately, and the synchronous snapshot swap delivers that inside the UI. It says nothing about the case where **one service writes and another reads**: billing reassigns a plan, then calls a product API whose replica is up to 5 seconds behind, and the product answers on the old plan.

Every mutating admin response returns its resulting `snapshotVersion`. Carry it forward:

```java
long v = adminResponse.snapshotVersion();          // from PUT /admin/v1/accounts/{id}/plan
Decision d = client.check("acct_9931", "seats", v); // resolved at or above v, or throws
```

- `check(account, capability, minSnapshotVersion)` throws `SnapshotBehindException` (carrying the replica's current version) rather than blocking, so the caller decides whether to retry, wait, or proceed on the older answer.
- `awaitVersion(v, timeout)` is the opt-in blocking form. It is **not** the default: silently turning a microsecond lookup into a multi-second wait is a bad trade to make on a caller's behalf.
- Omitting the version is fine and remains the common case — the 60-second promise still holds *(c28)*.

This is the Zanzibar *zookie* pattern reduced to its cheapest form. A plain monotonic integer suffices because there is a single writer, and therefore a single global order. See `research.md` §21.

## Threading and lifecycle

One daemon poller thread per client instance; construct one client per process and share it. `check` and `checkAll` are safe from any thread and lock-free — they read an `AtomicReference` to an immutable snapshot, so a sync in flight cannot be observed half-applied *(c31)*. `close()` stops the poller; in-flight decisions complete against the snapshot they already hold.
