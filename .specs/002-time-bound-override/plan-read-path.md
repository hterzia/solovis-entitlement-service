# Sub-plan: Separating the Snapshot from the Management Service

**Parent**: [`plan.md`](./plan.md) — this is its Phase 0, lifted out because it is a change to 001 rather than a part of 002, and it stands on its own.
**Spec**: [`spec.md`](./spec.md) · **Builds on**: [`001-entitlement-service/`](../001-entitlement-service/)
**Date**: 2026-08-09

## The change in one line

**The management service answers from SQLite. The snapshot becomes the replication artifact for consuming services and nothing else — including not being held in the service's own memory.**

Stated as the structure to end at: **service → SQLite, client → snapshot.**

## Why

001 built one snapshot to do two jobs: the management service's read model, *and* the model replicas consume. The two jobs want different things, and today the replication job loses.

`FullSnapshotWriter.overrideLine()` already strips `reason`, `createdBy` and `createdAt` on the way to the wire — but the in-memory snapshot holds them, because the management service resolves explanations from it. So commercially sensitive text (*"suspended pending investigation"*) sits in the management heap and is filtered out at the last moment. Once the management service reads from the record itself, the snapshot never needs those fields at all: `research.md` §2's data-governance boundary stops being a serialisation-time filter and becomes structural — the artifact cannot leak what it does not contain.

That is the standing argument. The immediate one is 002. Three things it needs are awkward against a snapshot and natural against the record:

- **A window becomes a predicate**, evaluated at the moment of asking, so the management service is exactly right at every instant with no scheduler involved in being correct.
- **Naming overrides that are not in force costs a query rather than permanent heap.** Against a snapshot, 002 c19 would force it to retain every override ever created — the largest risk in the parent plan, and one that grows for seven years.
- **Point-in-time stops being a separate mechanism.** The live path builds a view of *now* from the record; the historical path builds a view of *then*. Same code, different `asOf`, same unchanged `Resolver.explain()`.

## Why now

**The feed has no consumer.** `entitlement-client` is still a single `package-info.java`, so nothing anywhere reads the replication feed. The blast radius of changing what the snapshot is *for* is zero today, and will not be zero again.

## What does not change

Worth stating first, because it is most of the system:

| Untouched | Why |
|---|---|
| The feed's wire shape, `format`, `resolverContract` | A replica cannot tell this happened |
| `entitlement-client` | No release required — and it is where `Snapshot` now belongs |
| `entitlement-core` | `Resolver` takes an interface. `Snapshot`, `SnapshotBuilder` and `SnapshotMutator` stay, as the **client's** read model |
| `SnapshotFeedController`, `DeltaFeedService`, `SnapshotAssembler`, `FullSnapshotWriter` | The feed keeps working; only where it sources its model changes |
| Write paths and audit | Writes are unaffected; only reads move |

### What is removed

The first draft of this sub-plan kept `SnapshotHolder` inside the service to serve the feed, and ruled removal out of scope. That was the wrong call, and the structure above is why: **if only consuming services use snapshots, the management service has no business holding one.**

| Removed | Consequence |
|---|---|
| `SnapshotHolder` | Nothing in the service reads a snapshot, so there is nothing to hold |
| `SnapshotStartup` | Boot-time assembly goes with it, and so does the "no window where the port is open but the snapshot is empty" problem it exists to solve |
| `SnapshotPublisher`'s read-modify-write and `afterCommit` swap | Publishing becomes: insert a `snapshot_version` row carrying its delta. The class shrinks to almost nothing |

Two consequences worth naming before they surprise someone:

**`EntitlementDatabaseProperties`'s `write-pool-size == 1` validation loses its stated reason.** Its message says the pin is required *"because SnapshotPublisher's read-modify-write of the in-memory snapshot depends on write serialization"*. That read-modify-write is gone. A single writer remains correct — SQLite permits exactly one writer, and the audit trail's ordering depends on it — but the justification in the code would be actively misleading and must be reworded rather than left.

**`/v1/snapshot/full` assembles from SQLite per request.** That path is rare — a replica's first start, and a resync after falling past the delta horizon — so on-demand assembly is affordable. The case to keep in view is a fleet-wide restart, where every replica asks at once; a short-lived cache of the assembled body is the mitigation if it ever bites. This is the one thing the holder was buying, and it is cheaper to solve directly than to keep a read model nobody reads.

## The seam that makes this cheap

`Resolver.resolve()` and `Resolver.explain()` take an **`EntitlementView`**, an interface whose only implementation today is `Snapshot`. 001 wrote it that way deliberately — *"the interface exists so tests can supply a minimal fixture without constructing a full snapshot"*.

So this is **"add a second implementation"**, not "rewrite the engine".

## Inventory

16 `snapshotHolder.current()` call sites across 10 files. **All sixteen go**, but they go for three different reasons and at three different costs:

| Group | Files | Sites | Becomes |
|---|---|---|---|
| **Resolution reads** | `DecisionController` (4), `PlanAdminService` (2), `OverrideAdminService` (2), `AccountAdminService` (1) | 9 | `DatabaseEntitlementView`. **The substance** — these drive all 7 `Resolver` calls |
| **Version stamp** | `MetaController`, `CapabilityAdminController`, `PlanAdminController` | 3 | `SnapshotVersionRepository.findLatestVersion()`. Mechanical |
| **Feed** | `SnapshotFeedController` (2), `DeltaFeedService` (1) | 3 | Assemble on demand for a full body; latest version from SQLite for the rest |
| **Publish** | `SnapshotPublisher` (1) | 1 | Nothing — the read existed only to compute the next version, which the autoincrement now gives |

Six files to change, two classes to add, three to delete.

## The part that must not be missed

**Every repository reads through the write connection, and the write pool is pinned at one.**

All seven repositories inject `entitlementWriteJdbcClient`. The `entitlementReadJdbcClient` bean exists and **nothing uses it**. And `EntitlementDatabaseProperties` now *validates* that `writePoolSize` is exactly 1, because `SnapshotPublisher`'s read-modify-write of the in-memory snapshot depends on write serialisation.

Those two facts together are the trap. Today reads are rare — the hot path never touches SQLite — so funnelling them through one connection costs nothing. **Move every decision read onto SQLite without re-pointing the repositories and every read serialises behind the single connection that also serialises every write.** The system would still be correct and would fall off a cliff under load, and the load measurement would be meaningless.

So: repositories become read/write aware — write-path methods keep the write client, read-path methods take the read client — *before* anything is measured. This is task 1 for that reason, not because it is the most interesting.

## The two new classes

### `DatabaseEntitlementView`

Implements `EntitlementView` over the repositories. `snapshot/RowMappers` already converts every row to its domain type (`toCapability`, `toPlan`, `toPlanEntitlement`, `toOverride`), so this is wiring rather than new logic.

Two properties are load-bearing:

**Request-scoped and preloaded, never lazy.** `AccountAdminService.get()` loops every active capability — around 500 — calling `Resolver.explain` on each, and already performs a `capabilityRepository.findByKey()` *inside* that loop. A lazy view would multiply that existing N+1 by every capability. The view preloads once: capabilities with tiers, the account's plan and that plan's entitlements, the account's overrides. Four queries per request regardless of capability count. Delete the in-loop lookup while you are there.

**One read transaction.** v1 criterion 31 requires a decision to reflect one coherent moment, and four separate queries can straddle a concurrent commit. The view reads inside a single `@Transactional(readOnly = true)`, which under WAL gives it a stable read view for its lifetime. **Without this, Phase 0 silently weakens a v1 guarantee** — a decision could mix a new plan with the old plan's overrides, which is precisely what c31 forbids.

`snapshotVersion()` returns the latest published version, read from `snapshot_version` — so every response still carries the version it was resolved against and `minSnapshotVersion` keeps working. `SnapshotVersionRepository` gains a `findLatestVersion()` for this and for the three version-stamp call sites.

### `PlanPreviewView`

`PlanAdminService.preview()` computes its "after" state by building a hypothetical snapshot with `SnapshotMutator`. It needs an overlay: a decorator wrapping `DatabaseEntitlementView` with the pending plan-entitlement edits, so the preview resolves through the same resolver as a real decision — which is what makes the preview trustworthy rather than a separate approximation (v1 c24, c35).

Note `OverrideAdminService` gains a little code rather than shedding it: it still needs `SnapshotMutator` and `publish()` for the feed, and now also re-reads for its own response.

## What it unlocks

Immediately after this lands, and not before:

- **c19–c21.** `knownOverrides()` can return pending, ended and removed overrides from the record. The engine side is already built and tested; this is the missing supply. The parent plan deferred it rather than building it against the snapshot, precisely to avoid building and then unbuilding the heap growth.
- **c22–c29.** The point-in-time assembler becomes a sibling of `DatabaseEntitlementView` rather than a separate mechanism, and the history-replay property test compares two invocations of one implementation instead of a reconstruction against an original.
- **The midnight roller is demoted** from a correctness component to a publication one. If it runs late, replicas lag; the management service is still right. That also softens the Cloud Run CPU-throttling risk from "wrong answers" to "stale replicas".

## Tasks

Each ends somewhere reviewable.

1. **Read/write-aware repositories.** Read-path methods take `entitlementReadJdbcClient`; write-path methods keep the write client. No behaviour change, no new reads yet. *Done when: every existing test passes and no read-path method touches the write pool.*
2. **`DatabaseEntitlementView`**, preloaded and request-scoped, plus a test that it answers identically to a `Snapshot` built from the same data — the property the whole change rests on. *Done when: the two views are interchangeable for every method.*
3. **One read transaction**, with a test that a concurrent commit cannot be observed half-applied (v1 c31).
4. **Re-point `DecisionController`** (4 sites, 2 resolver calls). *Done when: `/v1` and the checker answer from the record.*
5. **Re-point `AccountAdminService.get()`** and delete the in-loop `findByKey`.
6. **`PlanPreviewView`**, re-point `PlanAdminService` (2 sites).
7. **Re-point `OverrideAdminService`** (2 sites), keeping its publish guards — a pending override still must not reach the feed, and removing one that was never in force still must publish nothing.
8. **`SnapshotVersionRepository.findLatestVersion()`**, and re-point the three version-stamp sites and `DeltaFeedService`.
9. **Source the feed's full body from SQLite** — `SnapshotFeedController` asks `SnapshotAssembler` per request, inside one read transaction so the header version cannot disagree with the body.
10. **Delete `SnapshotHolder` and `SnapshotStartup`; reduce `SnapshotPublisher`** to inserting a version row. Reword the `write-pool-size` validation message, which no longer describes why the pin exists. *Done when: nothing in `entitlement-service` holds a `Snapshot` between requests.*
11. **Assert the projection carries no explanation-only fields.** With no persistent snapshot the governance win is structural, but the transient body built for a full resync must still never carry reason text, and a test should say so.

Tasks 1–3 are prerequisites. 4–7 are independent of one another. 8–10 are the demolition and want to come last, once nothing reads the holder.

## Verification

- Every existing test passes unchanged. That is most of the assurance: the resolver is untouched, so behaviour that changes is a defect.
- A view-equivalence test (task 2) pinning that `DatabaseEntitlementView` and `Snapshot` agree.
- A coherence test (task 3) for c31.
- A test that the snapshot no longer carries reason text (task 8) — the data-governance win made structural.
- The feed's own tests untouched and passing, which is what says a replica cannot tell.

## The open question this forces

**v1 criteria 25–27** — 5,000 single-capability decisions per second at p99 ≤ 10 ms, whole-account at ≤ 50 ms, both held *while writes are happening* — were the stated reason for the in-memory read path (`research.md` §8). Moving the management service's own `/v1` onto SQLite means they must be re-homed or re-proven.

Two honest resolutions:

- **Demonstrate them on the replica path**, where product traffic actually lands, and set a separate operator-scale target for the management service's own HTTP surface. This matches how the system is meant to be used — `research.md` §1 says `/v1` exists for non-JVM callers, the UI checker and diagnostics, while products embed the SDK. It requires amending 001's `plan.md` and `research.md`, and it requires the SDK to exist.
- **Hold the management service to them and measure it.** Plausible for a ~50 MB database fully in page cache with a properly sized read pool, but it must be proven rather than assumed, and c27's "while plans and overrides are being changed" is exactly the case to watch.

**This does not block Phase 0.** Nothing in this sub-plan depends on which way it goes, and neither can be settled until 001's SDK and load harness exist. It needs deciding before the demonstration, not before the code.

## Effect on the parent plan

Mostly simplification. Nothing in [`plan.md`](./plan.md) is invalidated; three things get easier and one gets better.

**Phase 4, the midnight roll, gains atomicity it could not have had.** Today `SnapshotPublisher` derives the next version from the holder and defers the swap to `afterCommit`, which forces **exactly one publish per transaction** — so a midnight with fifty expiries meant fifty sequential transactions, and a model that was half-rolled in between. With the holder gone, version numbers come from the autoincrement, so **N transitions become one transaction with N version rows**: atomic, observed by replicas all-or-nothing, and needing no change to the feed's one-change-per-version contract. The parent plan's note about one publish per transaction should be rewritten as a property that *was* true rather than a constraint to design around.

**Phase 3's window filter in `SnapshotAssembler` matters more, not less.** It is now the only place windows are applied to what replicas receive, since there is no long-lived snapshot to mutate. Already implemented.

**Phase 5's point-in-time work is unchanged**, and its assembler becomes a sibling of `DatabaseEntitlementView` rather than a special case.

**The parent plan's "Scale/Scope" section can drop its snapshot sizing entirely.** It budgeted ~150 MB steady-state for a management snapshot retaining dead overrides. There is no management snapshot.

## Risks

| Risk | Assessment |
|---|---|
| **Reads left on the single write connection** | The one that turns a correct change into a slow one. Task 1 exists solely to close it, and it comes first |
| **Losing v1 c31** | Four queries can straddle a commit. Mitigated by one read-only transaction per resolution and pinned by test |
| **The account view's existing N+1 gets worse** | ~500 capabilities × a lookup each. Preloading fixes it and makes the view faster than it is today |
| **A pending override reaching replicas** | Already guarded in the write path and asserted by test; task 7 must not disturb it |
| **Fleet-wide restart stampeding full-resync** | Every replica assembling at once. Rare, and the mitigation is a short-lived cache of the assembled body rather than keeping a read model nobody reads |
| **The `write-pool-size` message left stale** | It would tell the next reader the pin exists for a reason that no longer applies. Task 10 rewords it; a single writer is still right, for SQLite's own reason |
| **Divergence between the tool and products becomes visible** | Deliberate, and already recorded in the spec (§4 and its limitations table): the tool is exactly right at every instant, products follow within sixty seconds, and midnight makes that a nightly, predictable window |
