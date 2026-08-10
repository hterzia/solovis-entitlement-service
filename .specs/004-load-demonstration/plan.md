# Implementation Plan: The Load Demonstration

**Date**: 2026-08-10 | **Spec**: [`spec.md`](./spec.md) | **Evidences**: `001-entitlement-service/spec.md` criteria 25–31

## Summary

A new reactor module, `entitlement-loadtest`, plus a bulk seed path in `entitlement-service`. Nothing in the decision path changes. The module is separate because the demonstration must exercise a **deployed** service over HTTP with data changing underneath it, which is the one thing a JUnit suite structurally cannot do.

## Technical Context

**Harness**: k6 via the `grafana/k6` Docker image (Docker is present; k6 is not installed natively and this avoids installing it). k6 is chosen over Gatling because the harness must drive the deployed HTTP surface from outside, and k6's scenario/threshold model expresses "p99 under X while Y churns" directly. JMH is rejected outright: it would prove the resolver is fast and prove nothing about the requirement.

**Target**: the service running as it would in production — `java -jar`, real SQLite file, WAL mode, snapshot in memory.

## The four scenarios

| Script | Criterion | What it does |
|---|---|---|
| `k6/decision-single.js` | 25 | 5,000 req/s sustained against `GET /v1/accounts/{id}/capabilities/{key}`, random account × capability, p99 threshold 10 ms |
| `k6/whole-account.js` | 26 | `GET /v1/accounts/{id}/entitlements`, measured in its own run, p99 threshold 50 ms |
| `k6/churn-writer.js` | 27 | Concurrent writer: plan entitlement edits and override create/remove through `/admin/v1`, at a stated rate, running throughout both scenarios above |
| `k6/freshness-probe.js` | 28, 29 | Writes a known change, then polls both the service and an embedded SDK replica until the change is visible; reports the delay at each |

`scripts/run-demo.sh` orchestrates seed → start service → churn → load → report, and is the single command criterion 8 requires.

## The bulk seed — the part that is not obvious

`DemoDataSeeder` writes through the real admin services on purpose, so the demo data is indistinguishable from operator-created data. That is exactly wrong for 100,000 accounts: `AccountAdminService.create` is one transaction, one audit row and **one `SnapshotPublisher.publish`** per account. Seeding through it would produce 100,000 snapshot versions and 100,000 in-memory snapshot rebuilds before the demonstration even starts.

So the seed needs a separate bulk path, and its correctness argument has to be explicit:

- One transaction for the whole seed, batched inserts, **one** `publish()` at the end.
- It must produce a database indistinguishable from one built the slow way — same rows, same invariants, one audit event per entity as §8 requires. The seed is not exempt from the audit trail; it is exempt from re-publishing the snapshot 100,000 times.
- It is gated behind its own property, separate from `entitlement.seed.enabled`, and refuses to run against a non-empty database.

Shape of the seeded estate (from `001/plan.md`, "Scale/Scope"): ~500 capabilities across ~15 areas, ~10 plans, 100,000 accounts, ~50,000 live overrides. Overrides are deliberately **not** spread evenly — a realistic estate has a heavy tail, and an even spread would make every account's resolution equally cheap and flatter the result.

## Interaction with the retention horizon

The churn writer publishes a snapshot version per write. A long run therefore produces a large `snapshot_version` table, which `SnapshotVersionPruner` will trim on its 7-day horizon — irrelevant within a single run, but the run should report the table's size so the growth rate is visible rather than inferred.

## Reporting

The output is a written result, not a green tick: observed p99 against promised p99 for each of 25–31, plus data size, churn rate, duration and hardware. `spec.md` §4 is explicit that a missed promise is a complete demonstration, so the script must report a miss and exit successfully rather than failing the run — a harness that exits non-zero on a missed threshold invites the thresholds to be quietly relaxed.

## What this plan does not do

No change to `entitlement-core`, `entitlement-client` or the decision path. If the demonstration finds a promise is not kept, the remedy is separate work under its own decision, per `spec.md` §2.
