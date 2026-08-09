# Sub-plan: Separating the Snapshot from the Management Service

**Parent**: [`plan.md`](./plan.md) — this is its Phase 0, lifted out because it is a change to 001 rather than a part of 002, and it stands on its own.
**Spec**: [`spec.md`](./spec.md) · **Builds on**: [`001-entitlement-service/`](../001-entitlement-service/)
**Date**: 2026-08-09

## The change in one line

**The management service answers from SQLite. The snapshot becomes the replication artifact for consuming services and nothing else.**

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
| `SnapshotFeedController`, `DeltaFeedService`, `SnapshotPublisher`, `SnapshotStartup`, `SnapshotHolder`, `SnapshotMutator` | This is exactly what the snapshot is for. It keeps being assembled, mutated, versioned and published |
| The feed's wire shape, `format`, `resolverContract` | A replica cannot tell this happened |
| `entitlement-client` | No release required |
| `entitlement-core` | `Resolver` takes an interface. Not one line changes |
| Write paths, audit, the one-publish-per-transaction rule | Writes are unaffected; only reads move |

**Explicitly out of scope:** removing the in-memory snapshot altogether and serving `/v1/snapshot/full` from SQLite. That is a defensible later cleanup — it would delete `SnapshotHolder` and the structural-sharing mutator — but `SnapshotPublisher` anchors version numbering on the holder, full resync is rare, and nothing in 002 needs it. Not now.

## The seam that makes this cheap

`Resolver.resolve()` and `Resolver.explain()` take an **`EntitlementView`**, an interface whose only implementation today is `Snapshot`. 001 wrote it that way deliberately — *"the interface exists so tests can supply a minimal fixture without constructing a full snapshot"*.

So this is **"add a second implementation"**, not "rewrite the engine".

## Inventory

16 `snapshotHolder.current()` call sites across 10 files. They are not equal:

| Group | Files | Sites | Action |
|---|---|---|---|
| **Replication** | `SnapshotFeedController` (2), `SnapshotPublisher` (1), `DeltaFeedService` (1) | 4 | **Unchanged** |
| **Version stamp only** | `MetaController`, `CapabilityAdminController`, `PlanAdminController` | 3 | Read the latest published version instead, or leave. Immaterial |
| **Resolution reads** | `DecisionController` (4), `PlanAdminService` (2), `OverrideAdminService` (2), `AccountAdminService` (1) | 9 | **The change** — driving all 7 `Resolver.resolve`/`explain` call sites |

Four files to change, two classes to add.

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

`snapshotVersion()` returns the latest published version, so every response still carries the version it was resolved against and `minSnapshotVersion` keeps working.

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
7. **Re-point `OverrideAdminService`** (2 sites) — keeping its publish path exactly as it is.
8. **Stop populating explanation-only fields in the published snapshot**, now that nothing reads them from it. *Done when: the in-memory snapshot holds no reason text, and a test asserts it.*

Tasks 1–3 are prerequisites. 4–7 are independent of one another and can land in any order.

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

## Risks

| Risk | Assessment |
|---|---|
| **Reads left on the single write connection** | The one that turns a correct change into a slow one. Task 1 exists solely to close it, and it comes first |
| **Losing v1 c31** | Four queries can straddle a commit. Mitigated by one read-only transaction per resolution and pinned by test |
| **The account view's existing N+1 gets worse** | ~500 capabilities × a lookup each. Preloading fixes it and makes the view faster than it is today |
| **A pending override reaching replicas** | Already guarded in the write path and asserted by test; task 7 must not disturb it |
| **Divergence between the tool and products becomes visible** | Deliberate, and already recorded in the spec (§4 and its limitations table): the tool is exactly right at every instant, products follow within sixty seconds, and midnight makes that a nightly, predictable window |
