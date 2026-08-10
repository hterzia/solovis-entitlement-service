-- --------------------------------------------------------- service_state
--
-- Numbered V3, not V2: the 002 (time-bound overrides) branch already carries
-- V2__override_windows.sql, and two migrations sharing a version is a hard Flyway startup failure.
-- Leaving the gap costs nothing — Flyway applies whatever versions it finds, in order.
--
-- A tiny key/value store for facts the service itself needs to remember across restarts, as
-- distinct from the business model (which lives in the tables of V1) and the audit trail (which
-- records what people did). Nothing here is ever read on a decision path.
--
-- Its first key is `conformance.digest`: the fingerprint of the conformance vector set this
-- service last announced to replicas. snapshot-feed.md defines a `conformance.changed` delta whose
-- job is to hand a running replica a replacement vector set so it re-runs its gate without waiting
-- for a full resync. Detecting "the compiled vectors differ from the ones already announced"
-- requires remembering what was announced, and the vectors themselves are compiled into
-- entitlement-core rather than stored, so there is nowhere else for that fingerprint to live.
--
-- Deliberately not part of `snapshot_version`: that table is pruned on a retention horizon
-- (SnapshotVersionPruner), and a fingerprint that disappeared after seven days would make the
-- service re-announce an unchanged vector set on the next restart.
CREATE TABLE service_state (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
