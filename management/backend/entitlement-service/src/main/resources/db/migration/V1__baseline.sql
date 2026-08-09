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
