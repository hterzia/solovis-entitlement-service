# Sub-plan: The Service Reads SQLite, Only Clients Hold Snapshots

**Parent**: [`plan.md`](./plan.md) — this is its Phase 0, lifted out because it is a change to 001 rather than a part of 002.
**Spec**: [`spec.md`](./spec.md) · **Builds on**: [`001-entitlement-service/`](../001-entitlement-service/)
**Date**: 2026-08-10 (revised)

## The change in one line

**The management service resolves from SQLite and holds no in-memory snapshot at all. A snapshot is a thing a consuming client holds.**

## Where things actually stand

Worth stating plainly, because the word "snapshot" currently names two different things in one process.

**The service does not read SQLite to make a decision. It reads its own in-memory snapshot.** Sixteen call sites across ten files take `snapshotHolder.current()`: `DecisionController` resolves `/v1` from it (4), `AccountAdminService`, `PlanAdminService` and `OverrideAdminService` resolve from it (5), and `MetaController`, `PlanAdminController`, `CapabilityAdminController`, `GlobalExceptionHandler` and `SnapshotVersionHeader` read the version off it. SQLite is the system of record and serves admin listings, but no decision touches it.

**The client is not the only snapshot user — today there are three.** The service's own decision path; the feed that serves clients (`SnapshotFeedController` → `FullSnapshotWriter`, `DeltaFeedService`); and `entitlement-client`'s `Replica`, which holds a core `Snapshot` and resolves locally. The first is the one that should not exist.

**`entitlement-client` is now fully built** — `Replica`, `DeltaApplier`, `FullSnapshotReader`, `ConformanceGate`, `DiskCache`, the poller and the wire mapping, with their own tests. An earlier draft of this sub-plan said the feed had no consumer and the blast radius was zero. **That is no longer true**, and it changes the safety story rather than the design: the feed's observable behaviour must now be preserved exactly, and the client's existing tests are what will say whether it was.

## The target

| | Management service | Consuming client |
|---|---|---|
| Reads from | SQLite, the record itself | Its own in-memory `Snapshot` |
| Holds a snapshot | **No** | Yes, one, replicated |
| Answers during an outage | n/a — it *is* the source | Yes, from what it last saw |
| Sees windows | Evaluated at the moment of asking | Never — receives only what is already in force |

One sentence per side, and no one has to ask which snapshot is meant.

## Why go the whole way

An earlier draft stopped half-way: the service would stop *resolving* from the snapshot but keep holding one to serve the feed. That leaves two snapshots with different purposes in one process, which is the confusion this is meant to remove.

The decisive point is that **the half-way version already moves every decision onto SQLite**, so the performance exposure (below) is identical either way. Going the whole way adds only one real piece of work — serving `/v1/snapshot/full` from SQLite — and in exchange deletes:

- `SnapshotHolder` and the atomic-swap machinery
- `SnapshotStartup`, and with it the full assembly every boot must complete before serving
- The **undocumented one-publish-per-transaction rule**: `SnapshotPublisher` derives the next version from `snapshotHolder.current()` and swaps `afterCommit`, so two publishes in one transaction silently compute the same version and the second mutates a stale base
- The stated justification for pinning `write-pool-size` to exactly 1 — *"SnapshotPublisher's read-modify-write of the in-memory snapshot depends on write serialization"*. That reason disappears (see below)
- An entire class of bug: the service's snapshot and SQLite disagreeing

It subtracts more than it adds.

## What is kept, and what is deleted

| Kept, untouched | Why |
|---|---|
| `entitlement-core` — `Snapshot`, `SnapshotBuilder`, `SnapshotMutator`, `Resolver`, conformance | The **client** uses all of it. `SnapshotMutator` stops having a service-side user and keeps its client-side one (`DeltaApplier`) |
| `entitlement-client` | Not one line. It cannot tell this happened |
| The feed's wire shape, `format`, `resolverContract` | Same |
| `snapshot_version` and `SnapshotVersionPruner` | Delta retention is unaffected; the horizon and `410 snapshot-too-old` keep working |
| Write paths, audit, `AuditRecorder` | Only reads move |

| Deleted | Replaced by |
|---|---|
| `SnapshotHolder` | `SnapshotVersionRepository.findLatest()` for the version; SQLite for the content |
| `SnapshotStartup` | Nothing. There is nothing to warm |
| The holder-mutation half of `SnapshotPublisher` | Inserting the `snapshot_version` row, which is all publishing ever needed to be |
| `SnapshotAssembler` as a startup component | The same walk, now feeding the wire directly |

## The two new classes

### `DatabaseEntitlementView`

Implements `EntitlementView` over the repositories. `Resolver` takes that interface and `Snapshot` is currently its only implementation — 001 wrote it that way deliberately (*"so tests can supply a minimal fixture without constructing a full snapshot"*), which is why **`entitlement-core` does not change**. `snapshot/RowMappers` already converts every row to its domain type, so this is wiring rather than new logic.

Three properties are load-bearing:

**Request-scoped and preloaded, never lazy.** `AccountAdminService.get()` loops every active capability — around 500 — calling `Resolver.explain` on each, and already does a `capabilityRepository.findByKey()` *inside* that loop. A lazy view would multiply that existing N+1 by every capability. Preload once: capabilities with tiers, the account's plan and its entitlements, the account's overrides. Four queries per request regardless of capability count. Delete the in-loop lookup while you are there.

**One read transaction.** v1 criterion 31 requires a decision to reflect one coherent moment. Today the holder gives that for free — an immutable object cannot change under you. Under this change, coherence comes from a single `@Transactional(readOnly = true)`, where WAL gives a stable read view for its lifetime. **This is the guarantee most at risk in the whole sub-plan**, because losing it is silent: four queries straddling a commit would mix a new plan with the old plan's overrides, which is exactly what c31 forbids.

**`snapshotVersion()` reads the latest published version inside that same transaction**, so every response still carries the version it resolved against, `minSnapshotVersion` keeps working, and the version and the data cannot disagree.

### `PlanPreviewView`

`PlanAdminService.preview()` builds its "after" state with `SnapshotMutator`. It becomes a decorator over `DatabaseEntitlementView` carrying the pending plan-entitlement edits, so the preview resolves through the same resolver as a real decision — which is what makes it trustworthy rather than a separate approximation (v1 c24, c35).

## Serving the feed from SQLite

The only genuinely new work, and smaller than it sounds.

- **`GET /v1/snapshot?since=`** — already pure SQLite. `DeltaFeedService` reads `snapshot_version.delta_json`. **No change.**
- **`GET /v1/snapshot/version`** — `SELECT MAX(version)`. No change of substance.
- **`GET /v1/snapshot/full`** — `FullSnapshotWriter.write(Snapshot, …)` becomes `write(EntitlementView, …)`. `DatabaseEntitlementView` already implements it, so **the same class serves decisions and the feed**. It must run inside one read transaction and stamp the version read in that same transaction, or a replica could load a payload that never existed as a coherent state.
- **`SnapshotPublisher`** — keeps writing the `snapshot_version` row and drops the holder mutation. Version numbering comes from the table's autoincrement rather than `current() + 1`, which is what removes the one-publish-per-transaction rule.

**`write-pool-size` needs re-justifying, not changing.** Its validation message names the holder's read-modify-write, which will no longer exist. A single writer is still right — SQLite permits one writer, and `--max-instances=1` makes it an infrastructure guarantee — so the constraint stays and the *reason* is rewritten. Leaving a stale justification in a validator is how a future reader relaxes a constraint that still matters.

## What this changes in the main 002 plan

002's own phases are unaffected in substance; three passages need rewording.

| Parent plan | Change |
|---|---|
| §1 "Where time is evaluated" | The two-column table stands as written. Its "management app / replicas" split becomes literally true rather than aspirational |
| §3 "The midnight roll" | It no longer mutates an in-memory snapshot via `SnapshotMutator.withOverrideAdded/withOverrideRemoved`. It writes one audit row and one `snapshot_version` row per transition. Simpler, and the one-publish-per-transaction caveat disappears with the holder |
| The already-built publication guards | `OverrideAdminService` currently decides whether to call `SnapshotMutator` + `publish` for a pending override. That becomes "write the delta row, or don't" — same rule, less machinery. The two phantom-event tests assert version movement and keep working unchanged |
| Baseline table | Stale: `entitlement-client` **is** built, so c14 is demonstrable; the load harness moved to [`004-load-demonstration`](../004-load-demonstration/) |

Unchanged: `resolverContract` stays 1, the feed shape is untouched, no coordinated client release.

## Tasks

1. **Read/write-aware repositories.** Read-path methods take `entitlementReadJdbcClient`; write-path methods keep the write client. *This is task 1 for a reason:* all seven repositories currently use the write client, the read client bean is unused, and the write pool is pinned at one. Move decision reads onto SQLite without this and every read serialises behind the one connection that also serialises every write — still correct, and it would fall off a cliff under load with a meaningless measurement.
2. **`DatabaseEntitlementView`**, preloaded and request-scoped, with a test that it answers identically to a `Snapshot` built from the same data. That equivalence is what the whole change rests on.
3. **One read transaction**, with a test that a concurrent commit cannot be observed half-applied (v1 c31).
4. **Re-point `DecisionController`** (4 sites, 2 resolver calls).
5. **Re-point `AccountAdminService.get()`**; delete the in-loop `findByKey`.
6. **`PlanPreviewView`**; re-point `PlanAdminService` (2 sites).
7. **Re-point `OverrideAdminService`** (2 sites), preserving its publication guards.
8. **`FullSnapshotWriter` takes an `EntitlementView`**; serve `/v1/snapshot/full` from SQLite in one read transaction.
9. **Version reads off the holder** — `MetaController`, `PlanAdminController`, `CapabilityAdminController`, `GlobalExceptionHandler`, `SnapshotVersionHeader` — move to `SnapshotVersionRepository.findLatest()`.
10. **Delete `SnapshotHolder` and `SnapshotStartup`**; simplify `SnapshotPublisher`; rewrite the `write-pool-size` justification.

1–3 are prerequisites. 4–7 are independent of each other. 8–9 must precede 10.

## Verification

- **The client's existing test suite is the primary safety net**, and it is now a real one. `FullSnapshotReaderTest`, `DeltaApplierTest`, `ConformanceGateTest` and `StubFeedServer` describe the feed's observable behaviour from the outside; if a replica cannot tell this happened, they pass untouched.
- A view-equivalence test (task 2).
- A coherence test (task 3) for c31.
- A test that the service's answers are unchanged before and after each re-point — the resolver is untouched, so any behavioural difference is a defect.

## The open question this forces

**v1 criteria 25–27** — 5,000 decisions per second at p99 ≤ 10 ms, whole-account ≤ 50 ms, both held *while writes are happening* — were the stated reason for the in-memory read path (`research.md` §8), and they now belong to [`004-load-demonstration`](../004-load-demonstration/).

Two honest resolutions, and the choice is a product one:

- **Demonstrate them on the replica path**, where product traffic is meant to land. `research.md` §1 says `/v1` exists for non-JVM callers, the UI checker and diagnostics, while products embed the SDK — which now exists. This matches how the system is designed to be used, and requires amending 001's `plan.md` and `research.md` to say so.
- **Hold the service's own `/v1` to them and measure it.** Plausible for a ~50 MB database fully in page cache with a properly sized read pool, but it must be proven, and c27's "while plans and overrides are being changed" is the case to watch.

**This does not block the work.** Nothing here depends on which way it goes; it needs deciding before 004 runs, not before task 1.

## Risks

| Risk | Assessment |
|---|---|
| **Reads left on the single write connection** | Turns a correct change into a slow one, and would invalidate 004's numbers. Task 1 exists solely to close it |
| **Losing v1 c31** | The failure is silent. Mitigated by one read-only transaction per resolution, pinned by test. The most important assertion in this sub-plan |
| **A full snapshot served from an inconsistent read** | A replica would load a state that never existed. Same mitigation: one transaction, and the version stamped from inside it |
| **A client noticing** | The feed's shape does not change, and the client's own tests are the check. If they need editing, something is wrong |
| **The account view's existing N+1** | ~500 capabilities × a lookup each. Preloading fixes it and leaves the view faster than today |
| **A stale justification outliving its reason** | The `write-pool-size` message. Rewrite it in the same task that removes the holder, or a future reader will relax a constraint that still matters |
