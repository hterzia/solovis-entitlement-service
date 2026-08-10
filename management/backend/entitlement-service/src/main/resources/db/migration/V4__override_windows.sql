-- ============================================================================
-- V4__override_windows.sql
-- Feature 002 — time-bounded overrides and point-in-time answers.
--
-- Numbered V4, not V2, and the number is load-bearing. This began life as V2 on
-- a branch cut before V3__service_state.sql existed on main. Flyway defaults to
-- out-of-order=false with validate-on-migrate=true, and the deployed database is
-- restored from GCS rather than rebuilt, so it already has V1 and V3 recorded: a
-- V2 arriving afterwards is a hard startup failure, not a warning. Never edit V3
-- to close the gap either — its checksum is recorded, and changing so much as a
-- comment fails validation the same way.
--
-- Additive only: two nullable columns and three indexes. Nothing here rewrites
-- an existing row, so it is safe to apply to a live database with Litestream
-- replicating underneath it.
--
-- Deliberately NOT here: widening audit_event's `source` CHECK to admit 'CLOCK'
-- and its `action` CHECK to admit 'BEGIN'/'END'. SQLite cannot alter a CHECK in
-- place, so that needs the twelve-step table rebuild, and audit_event is
-- referenced by snapshot_version.last_audit_seq — meaning the rebuild has to run
-- with foreign_keys=OFF, which cannot be done inside Flyway's transaction. It
-- lands as its own migration alongside the code that writes those rows
-- (002 plan, Phase 4), where its verification can be reviewed on its own.
-- ============================================================================

-- --------------------------------------------------------- override windows
-- 'YYYY-MM-DD' in the service zone (002 spec §3.1). NULL starts_on means "from
-- creation"; NULL expires_on means "until removed" — the v1 behaviour, and still
-- the ordinary case. The expiry day is inclusive (c4), so an override with
-- expires_on = '2026-12-31' is in force for the whole of 31 December.
--
-- SQLite's ALTER TABLE cannot add a CHECK, so `starts_on <= expires_on` is
-- enforced in WindowRules and covered by WindowRulesTest. The other two rules of
-- c7 — no wholly-past window, no back-dated start — compare against the clock
-- and are not expressible as a column constraint at all.
ALTER TABLE account_override ADD COLUMN starts_on  TEXT;
ALTER TABLE account_override ADD COLUMN expires_on TEXT;

-- The midnight roll asks "what begins today" and "what ended yesterday" (c13).
-- Partial, because the overwhelming majority of overrides carry no window.
CREATE INDEX ix_override_window_start ON account_override(starts_on)  WHERE starts_on  IS NOT NULL;
CREATE INDEX ix_override_window_end   ON account_override(expires_on) WHERE expires_on IS NOT NULL;

-- ------------------------------------------------------- audit by capability
-- v1 indexed audit_event by account, plan, actor and time — the three filters
-- §8 required — but not by capability, though the column has always been there.
-- Point-in-time needs exactly this to answer "what did this plan set for this
-- capability then" and "what was this capability's default then" (c23), and the
-- history screen exposes it to operators (c31).
CREATE INDEX ix_audit_capability ON audit_event(capability_id, seq DESC);
