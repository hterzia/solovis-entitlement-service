# Phase 1 — Data Model

**Feature**: Entitlement Service (v1) | **Date**: 2026-08-09 | **Storage engine**: SQLite (WAL)

Criterion references in the form *(c14)* point at the numbered acceptance criteria in [`spec.md`](./spec.md) §10; section references point at the same document.

---

## Value representation (used by three entities)

Three entities carry an entitlement value — `capability` carries two (default and off-value), `plan_entitlement` and `account_override` one each. All of them use the same shape, so it is defined once here.

A value is one of three variants, discriminated by the owning capability's `value_type`:

| Variant | Carries | Order (least → most generous) |
|---|---|---|
| `SWITCH` | `boolean` | `false` < `true` |
| `QUANTITY` | `long amount`, **or** the distinct marker `unlimited` | by `amount`; `unlimited` is strictly greater than every amount |
| `TIER` | a `tier_key` declared by the capability | by the capability's declared `ordinal` |

`unlimited` is a distinct variant and is never stored, compared or serialised as a large number *(c2)*. Tier order is data, not code, and travels to callers with every tier value *(c3)*.

Physically each value occupies four nullable columns plus one flag, with a `CHECK` that exactly the columns for the owning type are populated:

```
bool_value     INTEGER  -- 0/1, SWITCH only
qty_value      INTEGER  -- QUANTITY only, NULL when unlimited
qty_unlimited  INTEGER  -- 0/1, QUANTITY only
tier_value     TEXT     -- TIER only, FK-checked against capability_tier
```

---

## Logical model

### 1. Capability

One named thing an account may be allowed to do. Nothing is evaluable unless declared here (§3.1) — the registry is what stops ad-hoc checks creeping back.

| Field | Type | Null | Default |
|---|---|---|---|
| `id` | integer surrogate | no | autoincrement |
| `key` | string, dotted (`export.parquet`) | no | — |
| `area` | string, derived from `key` prefix | no | derived on write |
| `display_name` | string | no | — |
| `description` | string | yes | null |
| `value_type` | `SWITCH` \| `QUANTITY` \| `TIER` | no | — |
| `default_*` | value columns | no (one populated) | — |
| `has_off_value` | boolean | no | `0` |
| `off_qty`, `off_tier` | value columns | yes | null |
| `status` | `ACTIVE` \| `RETIRED` | no | `ACTIVE` |
| `retired_at` | timestamp | yes | null |
| `created_at`, `updated_at` | timestamp | no | now |

**Relationships**: 1 capability → 0..n `capability_tier` (exactly n ≥ 2 when `value_type = TIER`, otherwise 0); 1 → 0..n `plan_entitlement`; 1 → 0..n `account_override`.

**Validation rules**

| Rule | Traces to |
|---|---|
| `key` unique, non-empty, matches `^[a-z0-9]+(\.[a-z0-9_-]+)+$` (at least one dot, so an area always exists) | §3.1 grouping, c40 |
| `area` = substring of `key` before the first dot; derived on write, never supplied by the client | c40 |
| `value_type` is immutable after creation — a capability cannot be a switch on one plan and a quantity on another | §3.1, c1 |
| A default value is mandatory and must match `value_type` | §3.2 partial plans, c4 |
| `SWITCH` capabilities may not declare an off-value; theirs is `false`, inherently and always | §5 table |
| `QUANTITY` off-value, when declared, must be `0`; `unlimited` may never be an off-value | §5 table |
| `TIER` off-value, when declared, must be a declared `tier_key` of this capability | §5 table |
| `TIER` capabilities declare ≥ 2 tiers with contiguous ordinals from 0 | §4 ordering, c3 |
| Status transitions `ACTIVE → RETIRED` only. No delete, ever. No un-retire in v1 | §3.1, c8 |
| A retired capability is not evaluable and is excluded from whole-account responses | §6.3, c19, c20 |

**State transitions**

```
        create
   ∅ ──────────▶ ACTIVE ──── retire ────▶ RETIRED   (terminal)
                                            │
                              still readable in history and in
                              audit records; never deleted (c8)
```

### 2. Capability tier

The declared, ordered levels of a `TIER` capability. Exists as its own entity because the order is a caller-visible fact *(c3)*, not an implementation detail.

| Field | Type | Null | Default |
|---|---|---|---|
| `capability_id` | FK → capability | no | — |
| `tier_key` | string (`community`, `gold`) | no | — |
| `ordinal` | integer, 0 = least generous | no | — |
| `display_name` | string | no | — |

**Relationships**: n tiers → 1 capability (composite PK `capability_id, tier_key`).

**Validation rules**: `ordinal` unique within a capability; ordinals contiguous from 0; a tier referenced by any plan entitlement, override, capability default or off-value may not be removed (it would silently rewrite stored values); tiers may only be appended above the current maximum ordinal, because inserting in the middle would renumber existing stored values and change past meanings. *(§4, c3)*

### 3. Plan

A named set of capability values forming the baseline for every account on it (§3.2). Partial and flat.

| Field | Type | Null | Default |
|---|---|---|---|
| `id` | integer surrogate | no | autoincrement |
| `key` | string (`pro`) | no | — |
| `name` | string | no | — |
| `description` | string | yes | null |
| `status` | `ACTIVE` \| `ARCHIVED` | no | `ACTIVE` |
| `is_default_for_new_accounts` | boolean | no | `0` |
| `created_at`, `updated_at` | timestamp | no | now |

**Relationships**: 1 plan → 0..n `plan_entitlement`; 1 plan → 0..n `account` (an account has exactly one plan).

**Validation rules**

| Rule | Traces to |
|---|---|
| `key` unique, non-empty | §3.2 |
| No parent plan field exists. Plans are flat; inheritance is not expressible | §3.2, c5 |
| A plan lists only the capabilities it sets; absence is meaningful and resolves to the capability default | §3.2, c4 |
| Exactly one plan may be the default for new accounts, enforced by a partial unique index | §3.3, c7 |
| A plan with ≥ 1 account cannot be archived or deleted | §3.2, c6 |
| The default plan cannot be archived until the designation moves | §3.3, c7 |
| Editing a plan takes effect for every account on it, immediately on commit — no grandfathering | §3.2 |
| An edit may not be saved without the affected-account count having been computed and recorded in the audit event | c34 |

**State transitions**

```
   ∅ ──create──▶ ACTIVE ──archive──▶ ARCHIVED   (only when account count = 0
                                                 and not the default plan, c6)
```

### 4. Plan entitlement

One capability value set by one plan. The join is the entity — its absence is what makes plans partial.

| Field | Type | Null | Default |
|---|---|---|---|
| `plan_id` | FK → plan | no | — |
| `capability_id` | FK → capability | no | — |
| value columns | see above | one populated | — |
| `updated_at` | timestamp | no | now |

**Relationships**: composite PK `(plan_id, capability_id)` — a plan sets a capability at most once.

**Validation rules**: the value's variant must match the capability's `value_type` *(c1)*; a `TIER` value must be a declared tier of that capability; a retired capability may not be added to a plan (existing rows survive, so history stays legible) *(c8)*; deleting the row is the way to make the plan silent about the capability, which returns it to the capability default *(c4)*.

### 5. Account

The billable customer (§3.3). Flat — no parent/child, no resellers, no workspaces.

| Field | Type | Null | Default |
|---|---|---|---|
| `id` | integer surrogate | no | autoincrement |
| `external_id` | string, the caller-facing identifier | no | — |
| `name` | string | yes | null |
| `plan_id` | FK → plan | no | the designated default at creation |
| `plan_assigned_at` | timestamp | no | now |
| `plan_assignment_source` | `PERSON` \| `SYSTEM` | no | — |
| `plan_assignment_actor` | string | no | — |
| `status` | `ACTIVE` \| `CLOSED` | no | `ACTIVE` |
| `created_at`, `updated_at` | timestamp | no | now |

**Relationships**: n accounts → 1 plan (**exactly one**, never zero, never two) *(c6)*; 1 account → 0..n `account_override`.

**Validation rules**

| Rule | Traces to |
|---|---|
| `external_id` unique; it is the identifier every API and the SDK use | §6 |
| `plan_id` is `NOT NULL` — the schema itself forbids an account with no entitlements | §3.3, c7 |
| A new account is assigned the designated default plan; creation fails if no default is designated | §3.3, c7 |
| Every assignment change records actor **and** whether it came from a person or an upstream system | §3.3, c36 |
| Changing plan does **not** touch the account's overrides | §3.4, c14/c15 semantics |
| An unknown `external_id` is an error, never a denial | §6.3, c19 |

**State transitions**: plan assignment changes freely between `ACTIVE` plans; each change writes an audit event carrying before/after plan and source *(c32, c36)*. `ACTIVE → CLOSED` exists only so a departed customer stops appearing in operator lists; a closed account still resolves, because a product may still ask about it. No v1 surface sets `CLOSED` — there is no close route and no UI control (decision 2026-08-09); every v1 account is `ACTIVE`, and the state is reserved for a future offboarding flow.

### 6. Account override (GRANT / HOLD)

An exception attached to one account and one capability (§3.4). Open-ended, absolute, and always reasoned.

| Field | Type | Null | Default |
|---|---|---|---|
| `id` | integer surrogate | no | autoincrement |
| `account_id` | FK → account | no | — |
| `capability_id` | FK → capability | no | — |
| `kind` | `GRANT` \| `HOLD` | no | — |
| value columns | see above | one populated | — |
| `reason` | string, mandatory free text | no | — |
| `created_at`, `created_by`, `created_source` | timestamp, string, `PERSON`\|`SYSTEM` | no | — |
| `removed_at`, `removed_by`, `removed_reason` | timestamp, string, string | yes | null |

**Relationships**: n overrides → 1 account, n → 1 capability. **No uniqueness on `(account_id, capability_id)`** — an account may hold any number of GRANTs and HOLDs on the same capability, and they coexist safely because of how §4 combines them.

**Validation rules**

| Rule | Traces to |
|---|---|
| `reason` non-empty after trimming, enforced by a `CHECK` — an exception with no stated reason becomes unremovable in practice | §3.4, c9 |
| The value's variant must match the capability's `value_type` | c1 |
| A `TIER` value must be a declared tier of that capability | c3 |
| An override may not be created against a retired capability | §6.3, c8 |
| A GRANT may name a capability the account's plan does not mention at all | §3.2, c17 |
| Removal is a soft delete (`removed_at` set), never a row delete, so history stays legible and audit rows keep a live referent | §8, c33 |
| A removed override is invisible to resolution from the instant of commit, restoring the underlying value with no further action | c14, c15 |
| Overrides survive a plan change untouched — there is deliberately no cascade | §3.4 |
| An override is immutable from creation to removal — no edit operation exists; correcting one is removal plus a new override, each with its own reason and audit event | §3.4, §8 |

**State transitions**

```
   ∅ ──create (reason required)──▶ LIVE ──remove──▶ REMOVED   (terminal)
                                     │
                        participates in resolution only while LIVE
```

> **Known v1 limitation, carried from §12 and visible in this model as an absence**: there are no `starts_at` / `expires_at` columns, so every temporary promise is permanent until removed by hand (`future-spec.md` §1), and there is no category column, so "contractually agreed lower limit" and "suspended for fraud" are both HOLDs distinguished only by free text (`future-spec.md` §7).

### 7. Audit event

The append-only history behind §8. Also the service's logical clock.

| Field | Type | Null | Default |
|---|---|---|---|
| `seq` | integer, monotonic | no | autoincrement |
| `occurred_at` | timestamp | no | now |
| `actor_kind` | `PERSON` \| `SYSTEM` | no | — |
| `actor_id` | string | no | — |
| `source` | `UI` \| `BILLING` \| `API` \| `SEED` | no | — |
| `entity_type` | see CHECK below | no | — |
| `entity_id` | string | no | — |
| `action` | `CREATE`\|`UPDATE`\|`RETIRE`\|`ARCHIVE`\|`REMOVE`\|`ASSIGN`\|`DESIGNATE` | no | — |
| `account_id`, `plan_id`, `capability_id` | FKs, for filtering | yes | null |
| `before_json`, `after_json` | JSON text | yes | null |
| `reason` | string | yes | null |
| `affected_account_count` | integer | yes | null |

**Relationships**: optional FKs to `account`, `plan` and `capability` exist purely to serve the three required filters (§8: by account, by plan, by actor). They are nullable because a capability edit has no account.

**Validation rules**

| Rule | Traces to |
|---|---|
| Written in the **same transaction** as the change it records — a change without an audit row is impossible | §8, c32 |
| `UPDATE` and `DELETE` are rejected by triggers, not merely avoided by application code | §8, c33 |
| Filterable by account, plan and actor, each index-served | §8, c33 |
| Retained ≥ 24 months; no pruning job exists in v1, so retention cannot be breached by accident | §8, c33 |
| Plan-entitlement edits record `affected_account_count` as computed at save time | c34 |
| Plan-assignment changes record `actor_kind` distinguishing a person from an upstream system | §3.3, c36 |
| `reason` is mandatory for override create and carried on override removal | §3.4, c9 |

**State transitions**: none. A row is written once and never changes — that is the entity's entire contract.

### 8. Snapshot version

The published, immutable state of the model at one moment. It is what makes "one coherent moment per decision" *(c31)* a nameable thing and what SDK replicas synchronise against.

| Field | Type | Null | Default |
|---|---|---|---|
| `version` | integer, monotonic | no | autoincrement |
| `published_at` | timestamp | no | now |
| `last_audit_seq` | FK → audit_event.seq | no | — |
| `delta_json` | JSON text describing what changed | no | — |

**Relationships**: 1 version → 1 audit event (the change that produced it).

**Validation rules**: published in the same transaction as the change and the audit row, so version, state and history can never disagree *(c31)*; versions are strictly increasing and never reused; `delta_json` is sufficient for a replica at version *V* to reach *V+1* without a full refetch; rows older than a configured horizon (7 days) may be pruned, after which a lagging replica must full-resync — pruning versions is safe precisely because they are a transport concern, whereas pruning audit events is forbidden.

`delta_json` holds the **projected** form only — overrides as `(account, capability, kind, value)`, without `reason`, `created_by` or `created_at`. Consuming services need an answer, not an explanation, so the fields a trace is made of never leave this database. Overrides are immutable once created — creation and removal are their only mutations — so no write can ever change a field the projection omits. See `contracts/snapshot-feed.md`.

---

## How the entities produce a decision

Resolution reads only from a snapshot and touches four of the entities above. Stated as the §4 rule with its data sources:

1. **Baseline** — `plan_entitlement(account.plan_id, capability)` if the row exists, otherwise `capability.default_*`. The trace names which, so a defaulted `0` and an explicit plan `0` are distinguishable *(c22)*.
2. **GRANTs** — every LIVE override with `kind = GRANT` on that account and capability. Take the most generous. If the baseline is at least as generous as every GRANT, the baseline stands *(c11)*.
3. **HOLDs** — every LIVE override with `kind = HOLD`. Apply the most restrictive; it can only reduce *(c13, c14)*.
4. **`allowed`** — `true` unless the capability declares an off-value and the effective value equals it; `SWITCH` is off at `false` inherently *(§5, c18)*.

Nothing in this reads a timestamp or a creation order to produce the **value** — the result depends only on what exists at the moment of decision *(c12, c13, c16)*. Creation order surfaces in exactly one place, and it is presentational: when overrides of the same kind tie on the deciding value, the trace marks the newest — highest id — as the winner, so explanations are deterministic too. Labels move; values never do.

### Which fields the answer needs, and which only the explanation needs

| Field | `resolve()` → `(allowed, value)` | `explain()` → trace |
|---|---|---|
| `plan_entitlement` values, `capability.default_*`, `off_value`, tier ordinals | required | required |
| `account_override.kind`, value columns | required | required |
| `account_override.reason`, `created_by`, `created_at`, `id` | **never read** | required |

The right-hand column is exactly what is withheld from replicas. `resolve()` is the hot path — it runs in every consuming service and allocates nothing; `explain()` runs only in the management service, over the same arithmetic, and layers the trace on top. Because the two share their computation rather than duplicating it, a trace can never describe a different decision from the one that produced the value *(c24)*.

---

## Physical schema (SQLite)

```sql
-- ============================================================================
-- V1__baseline.sql
-- Connection pragmas (set per connection, not here):
--   journal_mode=WAL, synchronous=NORMAL, foreign_keys=ON,
--   busy_timeout=5000, temp_store=MEMORY
-- Timestamps are ISO-8601 UTC text: strftime('%Y-%m-%dT%H:%M:%fZ','now')
-- ============================================================================

-- ---------------------------------------------------------------- capability
CREATE TABLE capability (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    key                   TEXT    NOT NULL,
    area                  TEXT    NOT NULL,
    display_name          TEXT    NOT NULL,
    description           TEXT,
    value_type            TEXT    NOT NULL
                            CHECK (value_type IN ('SWITCH','QUANTITY','TIER')),

    default_bool          INTEGER CHECK (default_bool IN (0,1)),
    default_qty           INTEGER CHECK (default_qty >= 0),
    default_qty_unlimited INTEGER NOT NULL DEFAULT 0
                            CHECK (default_qty_unlimited IN (0,1)),
    default_tier          TEXT,

    has_off_value         INTEGER NOT NULL DEFAULT 0
                            CHECK (has_off_value IN (0,1)),
    off_qty               INTEGER CHECK (off_qty = 0),
    off_tier              TEXT,

    status                TEXT    NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE','RETIRED')),
    retired_at            TEXT,
    created_at            TEXT    NOT NULL,
    updated_at            TEXT    NOT NULL,

    -- exactly one default variant, matching value_type
    CHECK (
      (value_type = 'SWITCH'   AND default_bool IS NOT NULL
                               AND default_qty IS NULL AND default_qty_unlimited = 0
                               AND default_tier IS NULL)
   OR (value_type = 'QUANTITY' AND default_bool IS NULL AND default_tier IS NULL
                               AND ((default_qty IS NOT NULL AND default_qty_unlimited = 0)
                                 OR (default_qty IS NULL     AND default_qty_unlimited = 1)))
   OR (value_type = 'TIER'     AND default_tier IS NOT NULL
                               AND default_bool IS NULL AND default_qty IS NULL
                               AND default_qty_unlimited = 0)
    ),
    -- off-value shape per §5: SWITCH never declares one; QUANTITY's may only be 0
    CHECK (
      (has_off_value = 0 AND off_qty IS NULL AND off_tier IS NULL)
   OR (has_off_value = 1 AND value_type = 'QUANTITY' AND off_qty = 0 AND off_tier IS NULL)
   OR (has_off_value = 1 AND value_type = 'TIER' AND off_tier IS NOT NULL AND off_qty IS NULL)
    ),
    CHECK (status = 'ACTIVE' OR retired_at IS NOT NULL)
);
CREATE UNIQUE INDEX ux_capability_key        ON capability(key);
CREATE INDEX        ix_capability_area_status ON capability(area, status);
CREATE INDEX        ix_capability_status      ON capability(status);  -- whole-account scan (c20)

-- ----------------------------------------------------------- capability_tier
CREATE TABLE capability_tier (
    capability_id INTEGER NOT NULL REFERENCES capability(id),
    tier_key      TEXT    NOT NULL,
    ordinal       INTEGER NOT NULL CHECK (ordinal >= 0),
    display_name  TEXT    NOT NULL,
    PRIMARY KEY (capability_id, tier_key)
);
CREATE UNIQUE INDEX ux_capability_tier_ordinal ON capability_tier(capability_id, ordinal);

-- --------------------------------------------------------------------- plan
CREATE TABLE plan (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    key                         TEXT    NOT NULL,
    name                        TEXT    NOT NULL,
    description                 TEXT,
    status                      TEXT    NOT NULL DEFAULT 'ACTIVE'
                                  CHECK (status IN ('ACTIVE','ARCHIVED')),
    is_default_for_new_accounts INTEGER NOT NULL DEFAULT 0
                                  CHECK (is_default_for_new_accounts IN (0,1)),
    created_at                  TEXT    NOT NULL,
    updated_at                  TEXT    NOT NULL,
    -- an archived plan can never be the default for new accounts (c7)
    CHECK (status = 'ACTIVE' OR is_default_for_new_accounts = 0)
);
CREATE UNIQUE INDEX ux_plan_key ON plan(key);
-- at most one designated default, enforced by the schema (§3.3, c7)
CREATE UNIQUE INDEX ux_plan_single_default
    ON plan(is_default_for_new_accounts) WHERE is_default_for_new_accounts = 1;

-- --------------------------------------------------------- plan_entitlement
CREATE TABLE plan_entitlement (
    plan_id       INTEGER NOT NULL REFERENCES plan(id),
    capability_id INTEGER NOT NULL REFERENCES capability(id),
    bool_value    INTEGER CHECK (bool_value IN (0,1)),
    qty_value     INTEGER CHECK (qty_value >= 0),
    qty_unlimited INTEGER NOT NULL DEFAULT 0 CHECK (qty_unlimited IN (0,1)),
    tier_value    TEXT,
    updated_at    TEXT    NOT NULL,
    PRIMARY KEY (plan_id, capability_id),
    CHECK (
      (bool_value IS NOT NULL AND qty_value IS NULL AND qty_unlimited = 0 AND tier_value IS NULL)
   OR (qty_value  IS NOT NULL AND bool_value IS NULL AND qty_unlimited = 0 AND tier_value IS NULL)
   OR (qty_unlimited = 1      AND bool_value IS NULL AND qty_value IS NULL AND tier_value IS NULL)
   OR (tier_value IS NOT NULL AND bool_value IS NULL AND qty_value IS NULL AND qty_unlimited = 0)
    ),
    FOREIGN KEY (capability_id, tier_value)
        REFERENCES capability_tier(capability_id, tier_key)  -- NULL tier_value passes (c3)
);
CREATE INDEX ix_plan_entitlement_capability ON plan_entitlement(capability_id);

-- ------------------------------------------------------------------ account
CREATE TABLE account (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    external_id            TEXT    NOT NULL,
    name                   TEXT,
    plan_id                INTEGER NOT NULL REFERENCES plan(id),   -- never null (c7)
    plan_assigned_at       TEXT    NOT NULL,
    plan_assignment_source TEXT    NOT NULL
                             CHECK (plan_assignment_source IN ('PERSON','SYSTEM')),
    plan_assignment_actor  TEXT    NOT NULL,
    status                 TEXT    NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE','CLOSED')),
    created_at             TEXT    NOT NULL,
    updated_at             TEXT    NOT NULL
);
CREATE UNIQUE INDEX ux_account_external_id ON account(external_id);
CREATE INDEX        ix_account_plan        ON account(plan_id);  -- affected count (c34)

-- --------------------------------------------------------- account_override
CREATE TABLE account_override (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id     INTEGER NOT NULL REFERENCES account(id),
    capability_id  INTEGER NOT NULL REFERENCES capability(id),
    kind           TEXT    NOT NULL CHECK (kind IN ('GRANT','HOLD')),
    bool_value     INTEGER CHECK (bool_value IN (0,1)),
    qty_value      INTEGER CHECK (qty_value >= 0),
    qty_unlimited  INTEGER NOT NULL DEFAULT 0 CHECK (qty_unlimited IN (0,1)),
    tier_value     TEXT,
    reason         TEXT    NOT NULL CHECK (length(trim(reason)) > 0),   -- (c9)
    created_at     TEXT    NOT NULL,
    created_by     TEXT    NOT NULL,
    created_source TEXT    NOT NULL CHECK (created_source IN ('PERSON','SYSTEM')),
    removed_at     TEXT,
    removed_by     TEXT,
    removed_reason TEXT,
    CHECK (
      (bool_value IS NOT NULL AND qty_value IS NULL AND qty_unlimited = 0 AND tier_value IS NULL)
   OR (qty_value  IS NOT NULL AND bool_value IS NULL AND qty_unlimited = 0 AND tier_value IS NULL)
   OR (qty_unlimited = 1      AND bool_value IS NULL AND qty_value IS NULL AND tier_value IS NULL)
   OR (tier_value IS NOT NULL AND bool_value IS NULL AND qty_value IS NULL AND qty_unlimited = 0)
    ),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL)),
    FOREIGN KEY (capability_id, tier_value)
        REFERENCES capability_tier(capability_id, tier_key)
);
-- resolution lookup: live overrides for one account+capability (§4)
CREATE INDEX ix_override_live_account_cap
    ON account_override(account_id, capability_id) WHERE removed_at IS NULL;
-- whole-account load: every live override for one account (c20)
CREATE INDEX ix_override_live_account
    ON account_override(account_id) WHERE removed_at IS NULL;
-- "who else has an override on this capability", and snapshot assembly
CREATE INDEX ix_override_live_capability
    ON account_override(capability_id) WHERE removed_at IS NULL;

-- ------------------------------------------------------------- audit_event
CREATE TABLE audit_event (
    seq                    INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at            TEXT    NOT NULL,
    actor_kind             TEXT    NOT NULL CHECK (actor_kind IN ('PERSON','SYSTEM')),
    actor_id               TEXT    NOT NULL,
    source                 TEXT    NOT NULL
                             CHECK (source IN ('UI','BILLING','API','SEED')),
    entity_type            TEXT    NOT NULL
                             CHECK (entity_type IN ('CAPABILITY','CAPABILITY_TIER','PLAN',
                                                    'PLAN_ENTITLEMENT','ACCOUNT','ACCOUNT_PLAN',
                                                    'DEFAULT_PLAN','OVERRIDE')),
    entity_id              TEXT    NOT NULL,
    action                 TEXT    NOT NULL
                             CHECK (action IN ('CREATE','UPDATE','RETIRE','ARCHIVE',
                                               'REMOVE','ASSIGN','DESIGNATE')),
    account_id             INTEGER REFERENCES account(id),
    plan_id                INTEGER REFERENCES plan(id),
    capability_id          INTEGER REFERENCES capability(id),
    before_json            TEXT,
    after_json             TEXT,
    reason                 TEXT,
    affected_account_count INTEGER
);
-- the three filters §8 requires, each index-served (c33)
CREATE INDEX ix_audit_account ON audit_event(account_id, seq DESC);
CREATE INDEX ix_audit_plan    ON audit_event(plan_id,    seq DESC);
CREATE INDEX ix_audit_actor   ON audit_event(actor_id,   seq DESC);
CREATE INDEX ix_audit_time    ON audit_event(occurred_at);

-- append-only, enforced by the engine rather than by convention (c33)
CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_event
BEGIN SELECT RAISE(ABORT, 'audit_event is append-only'); END;
CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_event
BEGIN SELECT RAISE(ABORT, 'audit_event is append-only'); END;

-- --------------------------------------------------------- snapshot_version
CREATE TABLE snapshot_version (
    version       INTEGER PRIMARY KEY AUTOINCREMENT,
    published_at  TEXT    NOT NULL,
    last_audit_seq INTEGER NOT NULL REFERENCES audit_event(seq),
    delta_json    TEXT    NOT NULL
);
CREATE INDEX ix_snapshot_version_published ON snapshot_version(published_at);
```

### Indexes, and the access patterns that justify them

| Index | Serves |
|---|---|
| `ux_capability_key` | every lookup by capability key, from the API and from snapshot assembly |
| `ix_capability_area_status` | registry and plan-editor grouping, collapse and search *(c40)* |
| `ix_capability_status` | "every capability that is not retired" for whole-account responses *(c20)* |
| `ux_plan_single_default` | the schema-level guarantee of exactly one default plan *(c7)* |
| `ix_plan_entitlement_capability` | "which plans set this capability", shown when retiring one |
| `ix_account_plan` | the affected-account count before a plan edit *(c34)* |
| `ix_override_live_account_cap` | the single-capability resolution path *(§4)* |
| `ix_override_live_account` | the account view and whole-account resolution *(c20, c39)* |
| `ix_override_live_capability` | snapshot assembly and capability-level operator queries |
| `ix_audit_*` | the three required history filters plus retention queries *(c33)* |

Note that none of these indexes is on a decision hot path in production — decisions read the in-memory snapshot. They serve snapshot assembly, the operator UI, and the admin API. This is deliberate: the schema is optimised for correctness and for operators, and the latency targets are met by not querying it.

### Migration strategy

Flyway runs forward-only versioned SQL migrations from `entitlement-service/src/main/resources/db/migration` at application startup, which is safe without coordination because the management service is the only process that ever writes the file. `V1__baseline.sql` is the schema above; every later change is a new `V{n}__{description}.sql` and no migration is ever edited after it has been applied anywhere. SQLite's `ALTER TABLE` supports only column addition and renaming, so any constraint change, type change or column drop must be written explicitly as the documented twelve-step rebuild — create the replacement table, copy the data, drop the original, rename, then recreate every index and trigger — all inside the one migration file, with `foreign_keys` disabled for the duration of that migration and re-checked at the end. Two rules constrain what migrations may do: `audit_event` may only ever gain columns, because its triggers make rewriting rows impossible by design and its content is a legal record; and any migration that changes the meaning of a stored value (adding a tier in the middle of an existing order, say) is forbidden outright rather than handled, because it would silently rewrite the past. Rollback is by restoring a file backup, not by a down-migration; SQLite's single-file nature makes a pre-migration copy the cheapest and most reliable undo available, and the startup sequence takes one before applying anything.

---

## Entity relationship summary

```
   capability 1 ──── 0..n capability_tier
       │ 1                                       (declares the order tiers are
       │                                          compared by — c3)
       ├──── 0..n plan_entitlement n ──── 1 plan
       │                                    │ 1
       │                                    │
       │                                    └──── 0..n account
       │                                                │ 1
       └──────────── 0..n account_override n ───────────┘

   audit_event ──▶ optional FKs to account, plan, capability   (filters only)
   snapshot_version ──▶ audit_event.seq                        (the moment it captures)
```

Read the diagram as the resolution rule: a decision walks *account → plan → plan_entitlement* for its baseline, falls back to *capability* for the default, then folds in *account_override* rows — and never consults `audit_event` or `snapshot_version`, which exist for history and for replication respectively.
