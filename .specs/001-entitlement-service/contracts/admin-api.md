# Contract — Admin API

**Base**: `/admin/v1` | Shared conventions, value encoding and error model: [`README.md`](./README.md)

Backs the five operator screens of §9. Every route in this file mutates or reads operator state; none is on a decision hot path.

**Auth**: **not implemented in v1** (user decision — see `plan.md`, "Accepted deviations"). The **Role** column below records the role each route *will* require, so that adding authentication is a filter and a bean, not a redesign. Until then every route is open and the recorded actor is a configured stub, which means acceptance criterion 37 is not demonstrable and the service must not be exposed beyond a trusted network.

**Upstream-system integration is deferred with it** (decision 2026-08-09): in v1 every write comes from the operator SPA — no external system, billing included, integrates against these routes yet, and a `source: SYSTEM` assignment change *(c36)* is demonstrated with a simulated caller. Which routes billing eventually gets, and under what stability promise, is designed when authentication lands; the `billing-sync` examples below show the intended shape, not a live integration.

**Write semantics common to every mutating route**: the change, its audit event and the new snapshot version are committed in **one** transaction, and the in-memory snapshot is swapped before the response returns. An operator's next read therefore always shows their own change *(c30)*. Every mutating response carries the resulting `snapshotVersion` and `changeVisibleEverywhereWithinSeconds: 60`, which is the value the UI states wherever a change is saved *(c41)*.

That `snapshotVersion` is also the read-your-writes token for **other services**. A system caller that writes here and then needs a downstream product to see the change should carry the version forward as `minSnapshotVersion` — see [`java-client-sdk.md`](./java-client-sdk.md), "Read-your-writes across services". Criterion 30 covers the operator; nothing else does.

---

## Capabilities — screen 1

| Route | Role | Purpose |
|---|---|---|
| `GET /admin/v1/capabilities` | Viewer | List with `?area=`, `?q=`, `?status=ACTIVE\|RETIRED\|ALL`, `?groupBy=area` |
| `POST /admin/v1/capabilities` | Administrator | Declare a capability |
| `GET /admin/v1/capabilities/{key}` | Viewer | One capability, plus where it is used |
| `PATCH /admin/v1/capabilities/{key}` | Administrator | Edit display name, description, default, off-value |
| `POST /admin/v1/capabilities/{key}/tiers` | Administrator | Append a tier above the current maximum ordinal |
| `POST /admin/v1/capabilities/{key}/retire` | Administrator | Retire *(c8)* |

### `POST /admin/v1/capabilities`

```jsonc
// request
{
  "key": "reports.monthly",
  "displayName": "Monthly reports",
  "description": "Reports an account may generate per month.",
  "valueType": "QUANTITY",
  "default": { "type": "QUANTITY", "amount": 0 },
  "offValue": null,
  "tiers": null                       // required (≥2, ascending) when valueType is TIER
}
```

`201` returns the capability descriptor. `area` is derived from the key prefix and is not accepted from the client.

| Error | Status | `type` |
|---|---|---|
| Key already declared | 409 | `entitlement/validation-failed` |
| Key has no dot, so no area | 422 | `entitlement/validation-failed` |
| Default's `type` ≠ `valueType` | 422 | `entitlement/value-type-mismatch` |
| Off-value declared on a `SWITCH`, or a `QUANTITY` off-value ≠ 0 | 422 | `entitlement/validation-failed` |
| Fewer than two tiers on a `TIER` capability | 422 | `entitlement/validation-failed` |

### `PATCH /admin/v1/capabilities/{key}`

Accepts `displayName`, `description`, `default`, `offValue`. **`valueType` is absent from the request shape** — a capability cannot become a different type *(c1)*; attempting it returns 409 `entitlement/immutable-field`.

### `POST /admin/v1/capabilities/{key}/tiers`

```jsonc
{ "tier": "platinum", "displayName": "Platinum" }   // appended at max(ordinal)+1
```

Inserting between existing tiers is refused with 409 `entitlement/immutable-field`: renumbering would silently change the meaning of every stored value.

### `POST /admin/v1/capabilities/{key}/retire`

`200` with the retired descriptor. The response includes `usage: { plans: ["pro","enterprise"], liveOverrides: 14 }` so the operator sees what stops being evaluable. Retirement is permitted even when in use — the capability stays legible in history and the overrides keep their referent *(c8)*. Already retired → 409.

---

## Plans — screen 2

| Route | Role | Purpose |
|---|---|---|
| `GET /admin/v1/plans` | Viewer | All plans with account counts |
| `POST /admin/v1/plans` | Administrator | Create |
| `GET /admin/v1/plans/{key}` | Viewer | One plan with its entitlements |
| `PATCH /admin/v1/plans/{key}` | Administrator | Name, description |
| `POST /admin/v1/plans/{key}/entitlements/preview` | Administrator | Affected count + single-account preview *(c34, c35)* |
| `PUT /admin/v1/plans/{key}/entitlements` | Administrator | Apply the edit |
| `POST /admin/v1/plans/{key}/archive` | Administrator | Archive an empty plan *(c6)* |
| `PUT /admin/v1/settings/default-plan` | Administrator | Designate the default for new accounts *(c7)* |

### `GET /admin/v1/plans`

```jsonc
{
  "plans": [
    { "key": "free", "name": "Free", "status": "ACTIVE",
      "isDefaultForNewAccounts": true,  "accountCount": 71204, "entitlementCount": 12 },
    { "key": "pro",  "name": "Pro",  "status": "ACTIVE",
      "isDefaultForNewAccounts": false, "accountCount": 26890, "entitlementCount": 41 }
  ],
  "snapshotVersion": 48211
}
```

There is no `parentPlanKey` field anywhere in this contract. Plans are flat and inheritance is not expressible *(c5)*.

### `POST /admin/v1/plans/{key}/entitlements/preview` — the blast-radius gate

```jsonc
// request
{
  "set":   { "reports.monthly": { "type": "QUANTITY", "amount": 75 },
             "api.access":      { "type": "SWITCH",   "enabled": true } },
  "unset": [ "export.parquet" ],
  "previewAccount": "acct_9931"          // optional but expected by the UI (c35)
}
```

```jsonc
// 200 response
{
  "planKey": "pro",
  "affectedAccountCount": 26890,                       // (c34)
  "diff": [
    { "capability": "reports.monthly", "before": { "type": "QUANTITY", "amount": 50 },
                                       "after":  { "type": "QUANTITY", "amount": 75 } },
    { "capability": "api.access",      "before": null,
                                       "after":  { "type": "SWITCH", "enabled": true } },
    { "capability": "export.parquet",  "before": { "type": "SWITCH", "enabled": true },
                                       "after":  null,
                                       "note": "Falls back to the capability default (false)." }
  ],
  "previewAccount": {
    "account": "acct_9931",
    "effects": [
      { "capability": "reports.monthly",
        "before": { "allowed": true, "value": { "type": "QUANTITY", "amount": 0 },
                    "trace": { /* full trace, identical shape to the decision API */ } },
        "after":  { "allowed": true, "value": { "type": "QUANTITY", "amount": 0 },
                    "trace": { /* … */ } },
        "changed": false,
        "note": "No change for this account — a HOLD of 0 caps the result either way." }
    ]
  },
  "previewToken": "pv_01J8…"
}
```

The preview is a pure computation against a hypothetical snapshot; it writes nothing. The `previewAccount.effects` traces are produced by the same resolver as a real decision, which is what makes the preview trustworthy rather than a separate approximation *(c24, c35)*.

The `note` on an account whose effective value does not move is the point of the feature: it shows an operator that a plan rise is invisible to a suspended customer.

### `PUT /admin/v1/plans/{key}/entitlements`

Same `set`/`unset` body, plus `previewToken`. The token binds the applied edit to a preview the operator actually saw; a missing or stale token returns 409, so a plan edit affecting thousands of accounts cannot be made without its reach having been computed *(c34)*.

```jsonc
// 200 response
{
  "planKey": "pro",
  "affectedAccountCount": 26890,
  "snapshotVersion": 48212,
  "auditSeq": 90112,
  "changeVisibleEverywhereWithinSeconds": 60          // (c41)
}
```

Editing changes every account on the plan the moment it commits — there is no per-account audience option and no grandfathering flag (§3.2). Retired capability in `set` → 409 `entitlement/capability-retired-for-write`.

### `POST /admin/v1/plans/{key}/archive`

409 `entitlement/plan-in-use` when `accountCount > 0` *(c6)*; 409 `entitlement/default-plan-required` when it is the designated default *(c7)*.

### `PUT /admin/v1/settings/default-plan`

```jsonc
{ "planKey": "free" }
```

Recorded as a `DEFAULT_PLAN` / `DESIGNATE` audit event like any other change *(c32)*. Archived plan → 422.

---

## Accounts and overrides — screen 3

| Route | Role | Purpose |
|---|---|---|
| `GET /admin/v1/accounts` | Viewer | Search by `?q=`, filter by `?planKey=`, cursor-paged |
| `POST /admin/v1/accounts` | Administrator | Create; assigned the default plan *(c7)* |
| `GET /admin/v1/accounts/{external}` | Viewer | Plan, effective entitlements with source marking, overrides *(c39)* |
| `PUT /admin/v1/accounts/{external}/plan` | Administrator | Reassign *(c36)* |
| `POST /admin/v1/accounts/{external}/overrides` | Exception manager | Create a GRANT or HOLD *(c9)* |
| `DELETE /admin/v1/accounts/{external}/overrides/{id}` | Exception manager | Remove *(c14, c15)* |

### `GET /admin/v1/accounts/{external}` — the account view

```jsonc
{
  "account": "acct_9931",
  "name": "Northwind Capital",
  "status": "ACTIVE",
  "plan": { "key": "pro", "name": "Pro",
            "assignedAt": "2026-05-04T10:00:00.000Z",
            "assignedBy": "billing-sync", "source": "SYSTEM" },
  "snapshotVersion": 48211,

  "entitlements": [                                    // every non-retired capability (c39)
    { "capability": "reports.monthly", "area": "reports",
      "allowed": true, "value": { "type": "QUANTITY", "amount": 0 },
      "source": "HOLD",                                // CAPABILITY_DEFAULT | PLAN | GRANT | HOLD
      "sourceDetail": { "overrideId": "ovr_7788",
                        "reason": "Suspended pending billing investigation" } },
    { "capability": "seats", "area": "seats",
      "allowed": true, "value": { "type": "QUANTITY", "unlimited": true },
      "source": "PLAN", "sourceDetail": { "planKey": "pro" } },
    { "capability": "export.parquet", "area": "export",
      "allowed": false, "value": { "type": "SWITCH", "enabled": false },
      "source": "CAPABILITY_DEFAULT", "sourceDetail": null }
  ],

  "overrides": [
    { "id": "ovr_4471", "capability": "reports.monthly", "kind": "GRANT",
      "value": { "type": "QUANTITY", "amount": 200 },
      "reason": "Renewal concession — Q3 pilot",
      "createdBy": "j.okafor", "createdAt": "2026-06-02T09:12:44.000Z",
      "effectNow": "OVERRIDDEN_BY_HOLD" },
    { "id": "ovr_7788", "capability": "reports.monthly", "kind": "HOLD",
      "value": { "type": "QUANTITY", "amount": 0 },
      "reason": "Suspended pending billing investigation",
      "createdBy": "billing-bot", "createdAt": "2026-08-01T02:00:00.000Z",
      "effectNow": "WINNING" }
  ]
}
```

`source` is what criterion 39 asks for — each value marked as coming from a default, a plan, a GRANT or a HOLD, in one place. `effectNow` (`WINNING` · `OVERRIDDEN_BY_HOLD` · `SUPERSEDED_BY_GRANT` · `SUPERSEDED_BY_STRICTER_HOLD` · `NO_EFFECT_PLAN_MORE_GENEROUS` · `NO_EFFECT_NOT_MORE_RESTRICTIVE`) is derived from the same trace, so the override list and the entitlement list can never disagree; every trace outcome has a representable state, including a HOLD beaten by a stricter HOLD and a HOLD that binds nothing. Ties carry the trace's rule: the newer override takes the winning label.

### `POST /admin/v1/accounts/{external}/overrides`

```jsonc
{
  "capability": "reports.monthly",
  "kind": "GRANT",
  "value": { "type": "QUANTITY", "amount": 200 },
  "reason": "Renewal concession — Q3 pilot"       // mandatory, non-empty (c9)
}
```

`201` returns the created override, the account's new decision for that capability **with its trace**, `snapshotVersion` and `changeVisibleEverywhereWithinSeconds: 60`. Returning the resulting trace is what tells an operator immediately that their GRANT is being capped by an existing HOLD.

| Error | Status | `type` |
|---|---|---|
| Missing or blank reason | 422 | `entitlement/reason-required` |
| Value type ≠ capability type | 422 | `entitlement/value-type-mismatch` |
| Undeclared tier | 422 | `entitlement/unknown-tier` |
| Retired capability | 409 | `entitlement/capability-retired-for-write` |

No uniqueness check and no conflict error: an account may hold any number of GRANTs and HOLDs on one capability, and §4 combines them safely *(§3.4)*.

There is deliberately no edit route: **an override is immutable from creation to removal** (decision 2026-08-09). Correcting one is a `DELETE` and a fresh `POST`, each with its own reason and audit event — so no override's stated reason can ever drift from the value it justifies.

> Warning the operator about overrides that already exist on the same account and capability is **not** in v1, and was withdrawn from `future-spec.md` on 2026-08-09, so it is not planned scope either. The returned trace is the standing mitigation: an operator whose GRANT is being capped by an existing HOLD sees that in the response to their own write.

### `DELETE /admin/v1/accounts/{external}/overrides/{id}`

Optional body `{ "reason": "Investigation closed" }`. Soft-deletes and returns the account's new decision with its trace, so the operator sees the restored value immediately *(c14, c15)*.

> v1 gap, stated in §8 and `future-spec.md` §2: **any caller may remove a HOLD**, including a compliance suspension. Removal is audited but not prevented. With authentication deferred, this is currently unauthenticated as well.

### `PUT /admin/v1/accounts/{external}/plan`

```jsonc
{ "planKey": "enterprise", "source": "SYSTEM", "actor": "billing-sync", "reason": "Subscription upgraded" }
```

`source` distinguishes a person from an upstream system and is recorded on the audit event *(c36)*. Overrides are **not** touched *(§3.4)*; the response includes `retainedOverrideCount` so the operator can see what carried over. Archived target plan → 422.

---

## Checker — screen 4

```
GET /admin/v1/check?account={external}&capability={key}
```

**Returns exactly the payload of `GET /v1/accounts/{id}/capabilities/{key}`**, byte for byte, from `Resolver.explain()`. It is a separate route only so the SPA has one origin; it is not a separate implementation *(c24, c38)*. Same three errors, same statuses.

Also accepts `?override={ref}` in place of the capability, resolving the ref to its account and capability first. Consuming services carry these opaque refs and nothing else — no reason text reaches a replica — so this is the route that turns a line in a product's debug log into an explanation.

Since no consuming service can produce a trace, this route and its `/v1` twin are the estate's only diagnostic surface for "why".

---

## Change history — screen 5

```
GET /admin/v1/audit?account=&planKey=&actor=&entityType=&from=&to=&cursor=&limit=
```

The three filters §8 requires — account, plan, actor — plus a time window, cursor-paged descending by `seq`.

`nextCursor` is **`null` on the last page**, including a last page that happens to be exactly `limit` rows long. "This page is full" and "there is more" are different facts, and only the second one warrants a cursor — otherwise the history screen offers a next page that turns out to be empty.

```jsonc
{
  "events": [
    { "seq": 90112, "occurredAt": "2026-08-09T14:03:10.880Z",
      "actor": { "id": "a.reyes", "kind": "PERSON" }, "source": "UI",
      "entityType": "PLAN_ENTITLEMENT", "entityId": "pro", "action": "UPDATE",
      "planKey": "pro", "account": null, "capability": "reports.monthly",
      "before": { "type": "QUANTITY", "amount": 50 },
      "after":  { "type": "QUANTITY", "amount": 75 },
      "reason": null, "affectedAccountCount": 26890 }
  ],
  "nextCursor": "aud_90099"
}
```

There is **no** POST, PATCH or DELETE on this resource, and the underlying table rejects updates and deletes at the engine level. History only grows *(c33)*.

---

## Service metadata

```
GET /admin/v1/meta
```

```jsonc
{
  "changeVisibleEverywhereWithinSeconds": 60,
  "answerReuseMaxSeconds": 10,
  "snapshotVersion": 48211,
  "capabilityAreas": ["api","export","integration","reports","residency","seats","sla","support"]
}
```

The UI reads the 60-second promise from here rather than hard-coding it, so the number stated to operators *(c41)* and the number the service actually guarantees cannot drift apart.
