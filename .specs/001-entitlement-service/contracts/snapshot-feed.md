# Contract — Snapshot Replication Feed

**Base**: `/v1/snapshot` | Shared conventions and error model: [`README.md`](./README.md)

How a consuming service keeps a local replica of the model so it can decide locally, fast, and while the management service is down. Consumed by [`java-client-sdk.md`](./java-client-sdk.md); documented here so a non-JVM product can implement the same replica.

**What is replicated is the model, not answers**: capabilities (with tier orders), plans and their entitlements, each account's plan assignment, and live overrides. The consumer runs the resolution rule locally. A plan edit that changes 40,000 accounts is a few hundred bytes on this feed, because the accounts' plan assignments have not changed.

**What is deliberately *not* replicated is everything only an explanation needs.** Overrides travel as `(account, capability, kind, value)`. Their `reason`, `createdBy`, `createdAt` and id stay in the management layer, because only the operator UI renders the §6.1 trace and reason text is commercially sensitive — `future-spec.md` §16 warns that internal reasons like *"suspended pending investigation"* must not reach surfaces built for other audiences. A consumer that needs an explanation calls `GET /v1/accounts/{id}/capabilities/{key}`, which holds the complete record. See `plan.md`, "Recorded interpretations", for how this reads against criterion 21.

**Auth** (when implemented): service credential. Read-only.

---

## 1. Current version — the poll

```
GET /v1/snapshot/version
```

Deliberately trivial so it can be polled every 5 seconds by every replica at negligible cost.

```jsonc
{ "version": 48211, "publishedAt": "2026-08-09T14:03:10.900Z",
  "format": 1, "resolverContract": 1 }
```

`format` is the wire-format integer; `resolverContract` is the semantics integer, bumped whenever the resolution rule itself changes. A replica that does not recognise **either** must stop syncing and log loudly rather than guess — the first guards the shape of the payload, the second guards what the payload *means*. Cache headers: `Cache-Control: no-store`.

---

## 2. Full snapshot

```
GET /v1/snapshot/full
Accept-Encoding: gzip
```

Fetched once at replica startup, and again only after `entitlement/snapshot-too-old`. Response is **gzipped NDJSON** (`application/x-ndjson`), one record per line, streamed so neither side buffers the whole body. Roughly 5 MB raw / 1 MB gzipped at the stated scale.

```
{"kind":"header","version":48211,"format":1,"resolverContract":1,"publishedAt":"2026-08-09T14:03:10.900Z","counts":{"capabilities":512,"plans":9,"accounts":100000,"overrides":48734}}
{"kind":"capability","key":"reports.monthly","area":"reports","valueType":"QUANTITY","default":{"type":"QUANTITY","amount":0},"offValue":null,"tiers":null,"status":"ACTIVE"}
{"kind":"capability","key":"support","area":"support","valueType":"TIER","default":{"type":"TIER","tier":"community","ordinal":0},"offValue":null,"tiers":[{"tier":"community","ordinal":0},{"tier":"standard","ordinal":1},{"tier":"gold","ordinal":2}],"status":"ACTIVE"}
{"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":false,"entitlements":{"reports.monthly":{"type":"QUANTITY","amount":50},"api.access":{"type":"SWITCH","enabled":true}}}
{"kind":"account","external":"acct_9931","planKey":"pro"}
{"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly","kind":"GRANT","value":{"type":"QUANTITY","amount":200}}
{"kind":"conformance","id":"cv_017","model":{ /* self-contained fragment */ },"expect":{"allowed":true,"value":{"type":"QUANTITY","amount":0}}}
{"kind":"footer","version":48211,"recordCount":100563}
```

The `override` record carries **no `reason`, `createdBy` or `createdAt`** — see the note at the top of this file. `ref` is an opaque correlation handle: it lets a support engineer paste an id from a consumer's debug log into the operator UI, and it is meaningless to resolution.

Rules the replica must enforce:

- Records may arrive in any order **except** that `header` is first and `footer` is last.
- A snapshot without a matching `footer.version` is incomplete and must be discarded, not partially applied. This is what stops a truncated response from silently becoming a wrong answer.
- The whole body describes **one** version. There is no interleaving with concurrent writes; the service serialises the response from an immutable snapshot object *(c31)*.
- Retired capabilities are **included**, marked `status: "RETIRED"`, so a replica can return the `retired-capability` error rather than a silent denial *(c19)*.
- Overrides on the feed are live ones only. A removed override is expressed as a removal in the delta stream, never as a tombstone in the full snapshot.
- `conformance` records are self-contained: each carries its own miniature model fragment and the expected `(allowed, value)`, so a replica can evaluate it without reference to the real data. There are typically 40–60, dominated by the §5 worked-examples table.

### The conformance gate

Before a newly loaded snapshot is allowed to serve, the replica evaluates every `conformance` record with its own engine. **Any mismatch, or a `resolverContract` it does not implement, and the replica refuses to serve** — at startup it fails to construct; mid-life it keeps the previous snapshot and raises an alarm.

This exists because the resolution rule now runs in every consuming service, and a consumer that computes a wrong answer produces no trace to diagnose it with. Detection therefore has to happen before the first wrong answer rather than after. It is cheap: a few dozen evaluations of a rule that takes microseconds. See `research.md` §20.

---

## 3. Delta

```
GET /v1/snapshot?since={version}
```

Everything needed to move a replica from `since` to the current version.

```jsonc
{
  "format": 1,
  "fromVersion": 48208,
  "toVersion":   48211,
  "publishedAt": "2026-08-09T14:03:10.900Z",
  "changes": [
    { "version": 48209, "kind": "plan.entitlements",
      "planKey": "pro",
      "set":   { "reports.monthly": { "type": "QUANTITY", "amount": 75 } },
      "unset": [ "export.parquet" ] },

    { "version": 48210, "kind": "override.created",
      "ref": "ovr_9002", "account": "acct_1177", "capability": "seats",
      "overrideKind": "HOLD", "value": { "type": "QUANTITY", "amount": 100 } },

    { "version": 48211, "kind": "override.removed", "ref": "ovr_7788" }
  ]
}
```

### Change kinds

| `kind` | Payload | Replica action |
|---|---|---|
| `capability.upserted` | full capability record | replace by key |
| `capability.retired` | `key` | mark retired; keep it |
| `plan.upserted` | plan header | replace by key |
| `plan.entitlements` | `planKey`, `set{}`, `unset[]` | apply to that plan's map |
| `plan.archived` | `key` | mark archived |
| `plan.defaultChanged` | `key` | move the designation |
| `account.upserted` | `external`, `planKey` | replace by external id (covers creation and reassignment) |
| `override.created` | projected override record | add |
| `override.removed` | `ref` | drop |
| `conformance.changed` | replacement vector set | re-run the gate before serving |

Overrides are immutable once created — creation and removal are the only override mutations, and the admin API exposes no edit — so every override change on this feed is `override.created` or `override.removed`, and no write can ever touch a field the projection omits.

Changes are ordered by `version` ascending and **must be applied in that order**. Applying the list is the only way a replica reaches `toVersion`; a replica must not reorder or skip.

> Ordering matters for *replication*, not for *resolution*. §4's rule remains order-independent — two replicas that reach version 48211 by different paths hold identical state and return identical decisions *(c16)*.

### Errors

| Condition | Status | `type` | Replica action |
|---|---|---|---|
| `since` older than the retained horizon (7 days) | 410 | `entitlement/snapshot-too-old` | discard and `GET /v1/snapshot/full` |
| `since` greater than the current version | 422 | `entitlement/validation-failed` | discard and full-resync; this means the service was restored from a backup |
| `since` equals current | 200 | — | empty `changes[]`, `fromVersion == toVersion` |

---

## 4. Freshness budget

| Step | Budget |
|---|---|
| Change committed → snapshot swapped and version published, inside the write transaction | ~0 s |
| Replica poll interval | 5 s |
| Delta fetch and apply | < 1 s |
| **Worst case, change → visible in every replica** | **~6 s** *(allowance: 60 s, c28)* |

The margin is deliberate. A replica may miss several consecutive polls and still be inside the 60-second promise, and every answer a healthy replica gives is younger than the 10-second reuse bound *(c29)*. During an outage the replica knowingly exceeds both, because §11's posture outranks them — see [`java-client-sdk.md`](./java-client-sdk.md).
