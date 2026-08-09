# Contracts — Entitlement Service v1

**Date**: 2026-08-09 | Criterion references *(cNN)* point at [`spec.md`](../spec.md) §10.

This feature exposes four API surfaces plus the operator screens. Each has its own file:

| Surface | File | Audience |
|---|---|---|
| Evaluation API (§6) | [`decision-api.md`](./decision-api.md) | product services, any language |
| Snapshot replication feed | [`snapshot-feed.md`](./snapshot-feed.md) | SDK replicas |
| Admin API | [`admin-api.md`](./admin-api.md) | the operator SPA |
| Java client SDK | [`java-client-sdk.md`](./java-client-sdk.md) | JVM product services |
| Operator screens (§9) | [`ui-screens.md`](./ui-screens.md) | operators |

Everything below is shared by all of them.

---

## Conventions

- Base paths: `/v1/**` is product-facing and stable; `/admin/v1/**` backs the SPA and may change with it; `/actuator/**` is operational.
- All bodies are `application/json; charset=utf-8`. Timestamps are ISO-8601 UTC with milliseconds (`2026-08-09T14:03:11.482Z`).
- Accounts are addressed by their **external id**, never by the internal surrogate key. Capabilities are addressed by their **key** (`export.parquet`).
- Every response that carries a decision also carries `snapshotVersion` and `evaluatedAt`. A single response is always resolved against exactly one snapshot version — it can never mix a new plan with the old plan's overrides *(c31)*.
- Response header `X-Entitlement-Snapshot-Version: <integer>` accompanies every `/v1` response.
- **Explanations are produced by the management service alone.** Consuming services resolve locally from a replica that carries values but no reason text, authorship or timestamps, so `GET /v1/accounts/{id}/capabilities/{key}` and the operator UI's checker are the only sources of a §6.1 trace. `plan.md`, "Recorded interpretations", records how this reads against criterion 21.
- **Authentication is not implemented in v1** (see `plan.md`, "Accepted deviations"). Every endpoint below is open, and the actor recorded on writes comes from a configured stub. Endpoints are documented with the auth they will require so the shape does not have to change later.

## Value encoding

One encoding, used identically in every request and response across all four API surfaces.

```jsonc
{ "type": "SWITCH",   "enabled": true }

{ "type": "QUANTITY", "amount": 50 }
{ "type": "QUANTITY", "unlimited": true }        // distinct value, never a large number (c2)

{ "type": "TIER",     "tier": "gold", "ordinal": 2 }
```

- `QUANTITY` carries **either** `amount` (a non-negative integer) **or** `unlimited: true`, never both and never neither.
- `TIER` carries `ordinal` on **responses** so a caller can answer "at least tier X" without fetching the registry *(c3)*; on **requests** `ordinal` is ignored if sent — `tier` is authoritative.
- A value whose `type` disagrees with the capability's declared type is rejected with `entitlement/value-type-mismatch` *(c1)*.

## Capability descriptor

Returned wherever a caller needs to interpret values.

```jsonc
{
  "key": "support",
  "area": "support",
  "displayName": "Support level",
  "valueType": "TIER",
  "default": { "type": "TIER", "tier": "community", "ordinal": 0 },
  "offValue": null,                                  // or a value, per §5
  "tiers": [                                         // TIER only, ascending (c3)
    { "tier": "community", "ordinal": 0, "displayName": "Community" },
    { "tier": "standard",  "ordinal": 1, "displayName": "Standard"  },
    { "tier": "gold",      "ordinal": 2, "displayName": "Gold"      }
  ],
  "status": "ACTIVE"
}
```

## Error model

RFC 9457 `application/problem+json`. Every error carries a stable `type` slug — callers branch on `type`, never on message text.

```jsonc
{
  "type": "entitlement/unknown-capability",
  "title": "Unknown capability",
  "status": 404,
  "detail": "No capability is declared with key 'export.parqet'.",
  "instance": "/v1/accounts/acct_9931/capabilities/export.parqet",
  "capability": "export.parqet"
}
```

| `type` | HTTP | Condition |
|---|---|---|
| `entitlement/unknown-account` | 404 | No account with that external id *(c19)* |
| `entitlement/unknown-capability` | 404 | No capability declared with that key *(c19)* |
| `entitlement/retired-capability` | 409 | The capability exists but is retired, so it is not evaluable *(c19)* |
| `entitlement/value-type-mismatch` | 422 | A supplied value's `type` disagrees with the capability *(c1)* |
| `entitlement/unknown-tier` | 422 | A tier key not declared by that capability |
| `entitlement/reason-required` | 422 | An override was submitted without a non-empty reason *(c9)* |
| `entitlement/plan-in-use` | 409 | Archive or delete attempted on a plan with accounts *(c6)* |
| `entitlement/default-plan-required` | 409 | Account creation with no default plan designated, or archiving the default plan *(c7)* |
| `entitlement/capability-retired-for-write` | 409 | Plan entitlement or override attempted against a retired capability *(c8)* |
| `entitlement/immutable-field` | 409 | Attempt to change a capability's `valueType`, or to renumber existing tiers |
| `entitlement/snapshot-too-old` | 410 | A replica asked for a delta from a pruned version; full resync required |
| `entitlement/snapshot-behind` | 409 | A `minSnapshotVersion` was supplied that the responder has not yet reached; carries `currentVersion` |
| `entitlement/validation-failed` | 422 | Generic request validation, with a `violations` array |

**The three errors in §6.3 are errors, never denials.** An unknown account, an unknown capability and a retired capability each return their own status and slug. None of them is ever expressed as `allowed: false` — "we don't know" and "no" are different answers *(c19)*.

## Versioning and compatibility

`/v1` is additive-only: new fields may appear, existing fields never change meaning or disappear. A breaking change means `/v2` served alongside.

The snapshot feed carries **two** integers, because a replica can be wrong in two different ways:

- `format` — the wire shape. An old SDK meeting a new payload fails loudly instead of misreading it.
- `resolverContract` — the resolution semantics of §4. Bumped only when the rule itself changes, which `future-spec.md` §1 (time-bounded overrides) and §3 (relative grants) both would. A replica that does not implement the current contract refuses to serve rather than answering differently from its neighbours.

Version numbers alone are a claim, not a check, so the feed also carries conformance vectors that each replica evaluates against its own engine before serving. See [`snapshot-feed.md`](./snapshot-feed.md), "The conformance gate".
