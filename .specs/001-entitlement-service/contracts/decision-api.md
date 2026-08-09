# Contract — Evaluation API (spec §6)

**Base**: `/v1` | Shared conventions, value encoding and error model: [`README.md`](./README.md)

The product-facing surface. Two routes answer the two questions §6 poses, plus a registry route callers need to interpret values. Every response is resolved against exactly one snapshot version *(c31)*.

**This is the only surface that produces explanations.** Consuming services resolve locally through the SDK, which returns `(allowed, value)` and holds no trace data; route 1 here is where a full §6.1 trace comes from, and the operator UI's checker is the same route behind a different path. Products should not call route 1 on a request path — use the SDK — but it is the right thing to call from a support tool, a debug endpoint or a log enrichment job.

**Auth** (when implemented): service credential. Read-only; these routes never mutate anything.

---

## 1. Single capability

```
GET /v1/accounts/{accountExternalId}/capabilities/{capabilityKey}
```

Returns the decision **with its full trace** *(c21)*.

### Path parameters

| Name | Type | Notes |
|---|---|---|
| `accountExternalId` | string | The account's external id. Unknown → 404 `entitlement/unknown-account` |
| `capabilityKey` | string | Dotted key. Unknown → 404; retired → 409 |

### Query parameters

| Name | Type | Notes |
|---|---|---|
| `minSnapshotVersion` | integer, optional | Resolve at or above this version, or return 409 `entitlement/snapshot-behind` with the current version. Use it when acting on a change you just made — see [`java-client-sdk.md`](./java-client-sdk.md), "Read-your-writes across services". |

### 200 response

```jsonc
{
  "account":        "acct_9931",
  "capability":     "reports.monthly",
  "allowed":        true,
  "value":          { "type": "QUANTITY", "amount": 0 },
  "snapshotVersion": 48211,
  "evaluatedAt":    "2026-08-09T14:03:11.482Z",

  "trace": {
    "baseline": {
      "source":  "PLAN",                                  // PLAN | CAPABILITY_DEFAULT
      "planKey": "pro",
      "value":   { "type": "QUANTITY", "amount": 50 },
      "note":    "Plan 'pro' sets this capability."
    },

    "grants": [
      { "overrideId": "ovr_4471", "value": { "type": "QUANTITY", "amount": 200 },
        "reason": "Renewal concession — Q3 pilot", "createdBy": "j.okafor",
        "createdAt": "2026-06-02T09:12:44.000Z",
        "outcome": "WON" },
      { "overrideId": "ovr_2210", "value": { "type": "QUANTITY", "amount": 120 },
        "reason": "Migration goodwill", "createdBy": "s.patel",
        "createdAt": "2026-03-18T16:40:02.000Z",
        "outcome": "LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT" }
    ],
    "grantStep": {
      "applied": true,
      "winner":  "ovr_4471",
      "value":   { "type": "QUANTITY", "amount": 200 },
      "note":    "Most generous GRANT (200) beats the plan baseline (50)."
    },

    "holds": [
      { "overrideId": "ovr_7788", "value": { "type": "QUANTITY", "amount": 0 },
        "reason": "Suspended pending billing investigation", "createdBy": "billing-bot",
        "createdAt": "2026-08-01T02:00:00.000Z",
        "outcome": "WON" }
    ],
    "holdStep": {
      "applied": true,
      "winner":  "ovr_7788",
      "value":   { "type": "QUANTITY", "amount": 0 },
      "note":    "Most restrictive HOLD (0) caps the result."
    },

    "result": {
      "value":         { "type": "QUANTITY", "amount": 0 },
      "allowed":       true,
      "allowedReason": "NO_OFF_VALUE_DECLARED"
    }
  }
}
```

### Trace field semantics

| Field | Meaning |
|---|---|
| `baseline.source` | `PLAN` when the account's plan sets the capability; `CAPABILITY_DEFAULT` when it does not. This is what distinguishes a defaulted `0` from an explicit plan `0` *(c22)* |
| `grants[]`, `holds[]` | **Every** live override of that kind on this account and capability, winners and losers alike, each naming its own source: id, reason, author, creation time *(c21, c22)* |
| `grants[].outcome` | `WON` · `LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT` · `LOST_NOT_MORE_GENEROUS_THAN_PLAN` |
| `holds[].outcome` | `WON` · `LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD` |
| `grantStep.applied: false` | with `why`: `NO_GRANTS` or `PLAN_AT_LEAST_AS_GENEROUS` — a denial explained as fully as a grant *(c23)* |
| `holdStep.applied: false` | with `why`: `NO_HOLDS` or `HOLD_NOT_MORE_RESTRICTIVE` |
| `result.allowedReason` | `NO_OFF_VALUE_DECLARED` · `DIFFERS_FROM_OFF_VALUE` · `EQUALS_OFF_VALUE` — the only thing `allowed` depends on *(c18)* |

**Ties are deterministic.** When two or more overrides of the same kind tie on the deciding value, the one with the **highest override id is marked the winner — the newest wins**. Ids are monotonic and never change, so the marking is stable and independent of evaluation order; the effective value is identical whichever is marked, because the tie is on the value itself — criteria 12, 13 and 16 are value guarantees and are untouched by this rule. A GRANT that only ties the plan does not displace it: the plan stands and the grant step reports `PLAN_AT_LEAST_AS_GENEROUS`. And when the hold step does not apply, the most restrictive HOLD is still the one marked `WON` within its list; `holdStep.applied: false` is what records that it changed nothing.

The trace always reads as the four-step narrative of §4 — baseline, grant step, hold step, result — with the full candidate lists hanging off steps two and three. **Denial by absence is explicit**, never an empty response: a capability the plan does not mention and no override touches returns `baseline.source: "CAPABILITY_DEFAULT"` with `grantStep.applied: false, why: "NO_GRANTS"` *(c23)*.

This object is the single trace artefact, produced by `Resolver.explain()` in the management service and by nothing else. The operator UI renders exactly this payload; no replica can construct a competing one, because none of them holds the reason text, authorship or timestamps a trace names. There is no second, separately maintained human explanation, and now no second *machine* one either *(c24)*.

`explain()` and `resolve()` share their arithmetic — the trace describes the very computation that produced the value, rather than a reconstruction of it — so the `value` here always equals what the SDK returns for the same account, capability and snapshot version.

### Errors

| Condition | Status | `type` |
|---|---|---|
| Unknown account | 404 | `entitlement/unknown-account` |
| Unknown capability | 404 | `entitlement/unknown-capability` |
| Retired capability | 409 | `entitlement/retired-capability` |

None of these is ever returned as `allowed: false` *(c19)*.

### Response headers

```
X-Entitlement-Snapshot-Version: 48211
Cache-Control: max-age=10, stale-if-error=86400
```

`max-age=10` states the §7 reuse bound in the protocol itself; `stale-if-error` states the §11 outage posture. Both are obligations on the caller — see [`java-client-sdk.md`](./java-client-sdk.md).

---

## 2. Whole account

```
GET /v1/accounts/{accountExternalId}/entitlements
```

Every capability that is not retired, for one account, in a single request *(c20)*.

### 200 response

```jsonc
{
  "account":        "acct_9931",
  "planKey":        "pro",
  "snapshotVersion": 48211,
  "evaluatedAt":    "2026-08-09T14:03:11.482Z",
  "entitlements": [
    { "capability": "api.access",       "allowed": false, "value": { "type": "SWITCH", "enabled": false } },
    { "capability": "reports.monthly",  "allowed": true,  "value": { "type": "QUANTITY", "amount": 0 } },
    { "capability": "seats",            "allowed": true,  "value": { "type": "QUANTITY", "unlimited": true } },
    { "capability": "sla",              "allowed": false, "value": { "type": "TIER", "tier": "none", "ordinal": 0 } }
  ]
}
```

**No traces.** Returning hundreds of full traces on every page load contradicts §7; traces come from route 1 and from the UI checker (§6.2). This route calls `Resolver.resolve()`, which allocates no trace objects at all.

Every answer here is byte-identical to what route 1 would return for the same account, capability and snapshot version *(c20)* — guaranteed structurally, because `resolve()` and `explain()` are the same arithmetic over the same snapshot.

Ordering is by capability key. Retired capabilities are absent — not present-and-denied.

### Errors

| Condition | Status | `type` |
|---|---|---|
| Unknown account | 404 | `entitlement/unknown-account` |

---

## 3. Capability registry (read-only)

```
GET /v1/capabilities
GET /v1/capabilities/{capabilityKey}
```

Callers need the tier order to answer "at least tier X" *(c3)* and the off-value to interpret `allowed`. Returns the capability descriptor documented in [`README.md`](./README.md).

`GET /v1/capabilities` accepts `?area=` and `?status=ACTIVE|RETIRED|ALL` (default `ACTIVE`), and returns `{ "capabilities": [ … ], "snapshotVersion": 48211 }`.

`GET /v1/capabilities/{key}` returns a retired capability with `status: "RETIRED"` — reading the registry is not evaluating, so this is a 200, not the 409 that route 1 returns *(c8)*.

---

## Performance obligations

| Route | Target |
|---|---|
| `GET /v1/accounts/{id}/capabilities/{key}` | 5,000 req/s sustained, p99 ≤ 10 ms, held while plans and overrides are being written *(c25, c27)* |
| `GET /v1/accounts/{id}/entitlements` | p99 ≤ 50 ms, measured separately *(c26)* |

Both are served from the in-memory snapshot and touch no database.

Route 1 builds a full trace on every call, so it carries the allocation cost the whole-account route avoids — a few microseconds against a 10 ms budget, comfortably inside it. This is why the split matters elsewhere: `resolve()` is what runs 5,000 times a second inside each consuming service, where the trace would be pure garbage.
