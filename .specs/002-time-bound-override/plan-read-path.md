# Sub-plan: The Service Reads SQLite, Only Clients Hold Snapshots

**Parent**: [`plan.md`](./plan.md) — this is its Phase 0, lifted out because it is a change to 001 rather than a part of 002, and it lands on `main` before the 002 branch rebases onto it.
**Spec**: [`spec.md`](./spec.md) · **Builds on**: [`001-entitlement-service/`](../001-entitlement-service/)
**Date**: 2026-08-10 (settled) · **Status: complete — built and merged to `main`.** The earlier draft that kept a service-side holder for the feed is superseded; the decision and its alternatives are recorded in `DECISIONS.md` §13.

> **What shipped, where this document predicted otherwise.** `SnapshotHolder` and `SnapshotStartup` are **deleted outright** rather than retained for the feed — `SnapshotAssembler.assembleFull()` walks `DecisionReadDao` too, so replication reads the record like everything else. The new view is `snapshot/RecordBackedView`, built by `snapshot/RecordViewAssembler` (`pointView`, `accountView`, `pointViewInWriteTxn`), not the `DatabaseEntitlementView` named below. `SnapshotPublisher` returns the `snapshot_version` row's own autoincrement key, so the version counter lives in exactly one place and the one-publish-per-transaction rule this document inherited from the holder no longer applies. Read the file as the record of why the change was made; read the code for what it became.

## The change in one line

**The management service resolves from SQLite and holds no in-memory snapshot at all. A snapshot is a thing a consuming client holds.**

## Why, in three sentences

The in-memory holder was justified by v1 criteria 25–27, which have never been evidenced (004 has not run), while 002 has three concrete needs that are awkward against a held snapshot and natural against the record: explanations that name overrides *not in force* (c19–c21) without retaining them in heap for seven years, point-in-time as the same view-building code with a different `asOf`, and window standing that is exact at the moment of asking. The holder also carries the codebase's subtlest invariants — a version counter kept in two places that agree only by convention, a publish shape that is correct only because the write pool is size 1, and a one-publish-per-transaction rule nothing enforces. Deleting it removes all three and makes the data-governance boundary structural: the artifact that leaves the process cannot leak what the process never assembles into it.

## Where things stand (verified 2026-08-10, on `main`)

There are **19 direct `snapshotHolder.current()` reads across 13 files**, every one enumerated in the call-site table below. The client SDK is fully built (168 tests) and `ClientAgainstRealFeedTest` pins the wire byte-for-byte, so the feed's observable behaviour must be preserved exactly — and the client's suite is what will say whether it was.

Two facts make this change smaller than it looks:

1. **No migration is needed.** V1 already ships the decision-path indexes: `ix_override_live_account_cap ON account_override(account_id, capability_id) WHERE removed_at IS NULL` (V1 line 157), `ix_override_live_account`, unique keys on `account.external_id`, `capability.key`, `plan.key`, and the `plan_entitlement` primary key. The schema was decision-ready all along; only the read path never used it.
2. **Every point query already exists as a repository method**: `AccountRepository.findByExternalId`, `CapabilityRepository.findByKey`/`findTiers`/`findAll`, `PlanRepository.findById`, `PlanEntitlementRepository.find(planId, capabilityId)`/`findByPlan`, `AccountOverrideRepository.findLive(accountId, capabilityId)`/`findLiveForAccount`, `SnapshotVersionRepository.findLatest`. The new DAO copies their SQL verbatim onto the read pool; it invents nothing.

## The target

| | Management service | Consuming client |
|---|---|---|
| Reads from | SQLite, the record itself, one read transaction per request | Its own in-memory `Snapshot` |
| Holds a snapshot | **No** | Yes, one, replicated |
| Answers during an outage | n/a — it *is* the source | Yes, from what it last saw |
| Version comes from | `snapshot_version` autoincrement, read inside the same transaction | The feed's `header.version` / delta `toVersion` |

## What does not change

| Untouched | Why |
|---|---|
| `entitlement-client` — not one line | The wire is identical; a replica cannot tell this happened. Its 168 tests plus `ClientAgainstRealFeedTest` are the primary safety net |
| `entitlement-core` | `Resolver` takes `EntitlementView`, an interface. `Snapshot`/`SnapshotBuilder`/`SnapshotMutator` stay — the client's `Replica` and the conformance fixtures own them now, and the feed still serialises a `Snapshot` object |
| The feed's wire shape, `format`, `resolverContract`, all `DeltaChange` payloads | Every `publish` call site keeps constructing the exact same delta; only the in-memory mutation beside it is deleted |
| `SnapshotVersionRepository`, `SnapshotVersionPruner`, delta retention, `410 snapshot-too-old` | A version row exists so a replica can be carried from *V* to *V+1*; none of that is holder-dependent |
| `DemoDataSeeder`, admin validation, audit, error model | They write and read the record, which is now simply also what answers |

## The design, pinned

### 1. Transactions and beans (`config/SqliteConfig.java`)

Add a read-side transaction manager and mark the existing one `@Primary`:

```java
@Bean
@Primary   // NEW annotation on the EXISTING bean — see trap below
public PlatformTransactionManager entitlementTransactionManager(
        @Qualifier("entitlementWriteDataSource") DataSource entitlementWriteDataSource) {
    return new DataSourceTransactionManager(entitlementWriteDataSource);
}

@Bean
public PlatformTransactionManager entitlementReadTransactionManager(
        @Qualifier("entitlementReadDataSource") DataSource entitlementReadDataSource) {
    return new DataSourceTransactionManager(entitlementReadDataSource);
}
```

**Trap (this will break the whole app if skipped):** today there is exactly one `PlatformTransactionManager` bean, so every unqualified `@Transactional` in the admin services resolves to it. The moment a second bean appears, unqualified `@Transactional` throws `NoUniqueBeanDefinitionException` at runtime — `@Primary` on the write manager is what keeps all existing annotations meaning "the write transaction". Do not rename either bean.

Read transactions are declared `@Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)`. `readOnly` is a hint — leave `enforceReadOnly` alone; the xerial driver does not want `setReadOnly` games. SQLite opens the WAL read snapshot at the **first statement** of the transaction, and every statement after that sees the same committed state until commit. That is the c31 mechanism, and Task 2 proves it with a test before anything depends on it.

### 2. `store/DecisionReadDao.java` — the only class that talks to the read pool

One `@Component` constructed on `@Qualifier("entitlementReadJdbcClient")`. Methods, each copying the SQL and `RowMapper` lambda of the named existing repository method so the shapes cannot drift:

| DAO method | Copy SQL from | Notes |
|---|---|---|
| `latestVersion()` | `SnapshotVersionRepository.findLatest` | Returns `long`, 0 when the table is empty (matches `SnapshotAssembler`'s default) |
| `account(String externalId)` | `AccountRepository.findByExternalId` | **Apply the same status predicate as `findAllActive()`** — a CLOSED account must stay invisible here, or `/v1` stops raising `entitlement/unknown-account` for it (§6.3 drift) |
| `planKeyById(long planId)` | `PlanRepository.findById` | Only the key is needed |
| `capabilityByKey(String key)` + `tiers(long capabilityId)` | `CapabilityRepository.findByKey` / `findTiers` | |
| `planEntitlement(long planId, long capabilityId)` | `PlanEntitlementRepository.find` | |
| `liveOverrides(long accountId, long capabilityId)` | `AccountOverrideRepository.findLive` | |
| `liveOverridesForAccount(long accountId)` | `AccountOverrideRepository.findLiveForAccount` | Whole-account slice |
| `activeCapabilities()` | `CapabilityRepository.findAll(null, "ACTIVE", null)` | |
| `allCapabilities(area, status, query)` | `CapabilityRepository.findAll` | For the `/v1/capabilities` registry routes |
| `allTiers()` | new: `SELECT * FROM capability_tier ORDER BY capability_id, ordinal` | One query, grouped in memory — never one `findTiers` per capability inside a whole-account resolve (500 point reads would eat the c26 budget) |
| `entitlementsForPlan(long planId)` | `PlanEntitlementRepository.findByPlan` | Whole-account slice |

Row-to-domain mapping reuses `snapshot/RowMappers` exactly as `SnapshotAssembler.assembleFull()` does today — read that method first; it is the reference for every mapping decision, including how `account_override` rows become `AccountOverride` and how tier rows order themselves.

### 3. `snapshot/RecordBackedView.java` — plain data, built eagerly, immutable

A final class implementing `EntitlementView`. **All data is loaded by the assembler inside the transaction; the view itself never touches JDBC.** That keeps the transaction boundary visible in one place and makes the view trivially testable. Two loading shapes:

- **Point view** (single check, checker, override mutation responses): `snapshotVersion` + one account + one capability (with tiers) + its plan entitlement + the live overrides for that one pair. Four indexed lookups plus the version read. The feed-only interface methods (`capabilities()`, `accountAssignments()`, `allLiveOverrides()`, `plans()`, `plan()`, `activeCapabilities()`) throw `UnsupportedOperationException` with a message naming the method — `Resolver.resolve`/`explain` touch none of them for a single pair, and a loud throw beats a silent empty answer if a future caller reaches for one.
- **Account slice** (whole-account, admin account detail): `snapshotVersion` + the account + all active capabilities + all tiers (one query) + all entitlements for the account's plan + all live overrides for the account. Five bulk queries; `activeCapabilities()`, `planEntitlement()`, `liveOverrides()` then answer from in-memory maps keyed the same way `Snapshot` keys them.

One extra affordance: `withoutOverride(long overrideId)` returns a copy with that override filtered out of its lists — this replaces `SnapshotMutator.withOverrideRemoved` in `OverrideAdminService.removalPreview` (the "what would the answer become" read at line 121). Preview and removal keep answering through the same `Resolver.explain`, so they still cannot disagree.

### 4. `snapshot/RecordViewAssembler.java` — the one place views are built

`@Component` with **both** `JdbcClient`s injected. Public surface:

```java
RecordBackedView pointView(String accountExternalId, String capabilityKey);      // read pool
RecordBackedView accountView(String accountExternalId);                          // read pool
RecordBackedView pointViewInWriteTxn(String accountExternalId, String capabilityKey); // write pool
```

The `-InWriteTxn` variant runs the identical queries through the write `JdbcClient`. It exists for one reason: a mutation response's explanation must reflect the write that was just made, before commit — the write connection sees its own uncommitted rows, the read pool cannot. Internally all three share private query helpers that take the `JdbcClient` as a parameter, so the SQL exists once. (Bonus this buys for 002 later: the create-pending path's response explanation can finally *see* the pending override it just stored and name it `NOT_IN_FORCE_PENDING`.)

Unknown account and unknown/retired capability are **not** the assembler's business: it loads what exists and leaves `Optional.empty()` in the view; `Resolver.lookUp` keeps throwing the three §6.3 errors exactly as today. Do not pre-empt them in the assembler — the error taxonomy has one owner.

### 5. `api/DecisionReadService.java` — the transaction boundary for every `/v1` read

A `@Service` whose public methods each carry `@Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)`. `DecisionController` becomes a thin mapper over it (controllers keep no `@Transactional` — the boundary lives in one service). Methods mirror the four routes plus the registry pair:

- `single(account, capabilityKey, minSnapshotVersion)` → point view; the `minSnapshotVersion` guard moves here unchanged: `view.snapshotVersion() < min` → `EntitlementApiException(SNAPSHOT_BEHIND, …, Map.of("currentVersion", view.snapshotVersion()))`. Same code, new version source — the handshake stays honest because version and model come from the same transaction.
- `whole(account)` → account slice; iterate `view.activeCapabilities()` sorted by key, `Resolver.resolve` each against the **same view instance** — one coherent moment for the whole response (c31), exactly as one `Snapshot` gave before.
- `capabilityList(area, status)` / `capabilityOne(key)` → DAO capability queries + `latestVersion()` in the same transaction, so the reported `snapshotVersion` describes the same moment as the list.

Every response header keeps being stamped at the producing site from `view.snapshotVersion()` — the `SnapshotVersionHeader` discipline is unchanged, only the source moved from the holder to the transaction.

### 6. `SnapshotPublisher` — twenty lines become eight

```java
public long publish(long lastAuditSeq, DeltaChange delta) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new IllegalStateException(
            "SnapshotPublisher.publish must be called from within an active transaction.");
    }
    String deltaJson = DeltaJson.write(delta);
    return snapshotVersionRepository.insert(new SnapshotVersionRow(
        null, Timestamps.iso(clock.instant()), lastAuditSeq, deltaJson));
}
```

The `Mutation` functional interface, the holder read, the `+1` arithmetic, and the `afterCommit` synchronization are all deleted. **The returned version is the autoincrement key** — the number that was maintained in two never-reconciled places now has exactly one home, and the undocumented one-publish-per-transaction rule dissolves (two publishes in one transaction now simply produce two consecutive rows). The guard stays: an autocommitted `snapshot_version` row outside its business transaction would still be a lie in the feed.

### 7. The feed serialises one transaction

`SnapshotAssembler` survives as the **feed's** assembler — no longer a startup component. A new `@Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)` service method (put it on `DeltaFeedService` or a small `FeedReadService`) assembles the full `Snapshot` **inside** the transaction and returns the immutable object; `SnapshotFeedController.full()` then streams it after the transaction closes. Assembling in-transaction is the point — the body must describe one version that actually existed (c31 on the wire); streaming the already-immutable object afterwards is free, exactly as it is today. `FullSnapshotWriter` is untouched. Convert `SnapshotAssembler`'s repositories usage as-is (they run on the write client today; either move its reads onto the DAO or leave the repositories — but the assembly must run inside the read transaction, so route its queries through the read pool; copying the three `findAll*` calls onto `DecisionReadDao` is the clean shape).

`DeltaFeedService.since()` gains the same annotation and replaces `snapshotHolder.current().snapshotVersion()` with `latestVersion()` — `current` and the delta rows must come from one transaction, or a write landing between the two reads fabricates a gap.

`version()` drops the holder for `SnapshotVersionRepository.findLatest()`: version and `publishedAt` come from the same row, which retires the moving-`publishedAt` fallback coupling for the ordinary case (keep the fallback for the genuinely-empty-table case). The pruner's keep-guard becomes `findLatest().version` — same semantics: never prune the row the feed is describing.

## Call-site table — all 19, with their replacement

| File:line | Today | Becomes |
|---|---|---|
| `api/DecisionController.java:53,75,92,110` | resolve/list from holder | delegate to `DecisionReadService` (§5) |
| `api/SnapshotFeedController.java:40` | version from holder | `findLatest()` row (§7) |
| `api/SnapshotFeedController.java:50` | serialise held snapshot | assemble in read txn, stream after (§7) |
| `snapshot/DeltaFeedService.java:29` | current version from holder | `latestVersion()` inside the same read txn (§7) |
| `snapshot/SnapshotPublisher.java:47` | base for `+1` and swap | deleted (§6) |
| `snapshot/SnapshotVersionPruner.java:81` | serving version guard | `findLatest().version` (§7) |
| `admin/MetaController.java:17` | version + areas from snapshot | `findLatest()` + `SELECT DISTINCT area FROM capability` via DAO; response fields identical (c41's 60 still comes from here) |
| `admin/PlanAdminController.java:25`, `admin/CapabilityAdminController.java:35` | version stamped on list responses | `latestVersion()` via DAO — one query; the list already came from the record |
| `admin/service/AccountAdminService.java:102` | account detail explains every capability against holder | `accountView(external)` once, explain each capability against that one instance — same coherence, keep the per-capability `capRow` lookups as they are |
| `admin/service/PlanAdminService.java:122` | preview reads before-values + version | before-values from `PlanEntitlementRepository.find` (already injected), version for `PreviewTokenCodec` from `latestVersion()` |
| `admin/service/PlanAdminService.java:169` | apply recomputes preview token against holder version | `latestVersion()` through the **write** connection (it is inside the write txn); token semantics preserved — a write between preview and apply still changes the version and voids the token |
| `admin/service/OverrideAdminService.java:88,164` | mutate-then-publish, explain against `next` | drop the mutation; `publish(auditSeq, sameDelta)`; explanation from `pointViewInWriteTxn(...)` (sees the uncommitted write) |
| `admin/service/OverrideAdminService.java:121` | removal preview via `SnapshotMutator.withOverrideRemoved` | `pointView(...).withoutOverride(id)` (§3) |
| `error/GlobalExceptionHandler.java:167` | version-at-failure-time from holder | `findLatest()` via DAO, keep the `Optional.empty()` fallback when the table is empty; semantics stay "advisory on errors", now honestly sourced |

Every other `publish` call site (`CapabilityAdminService` ×4, `PlanAdminService` ×5, `AccountAdminService` ×2) follows the `OverrideAdminService` pattern: **keep the `DeltaChange` construction character-for-character**, delete the `SnapshotMutator` line and the holder read, take the version from `publish`'s return. Where the response needs an explanation, use `pointViewInWriteTxn`/an account view on the write connection.

## Deleted outright

`snapshot/SnapshotHolder.java`, `snapshot/SnapshotStartup.java`, both their tests, and the `SnapshotStartup` constructor argument on `ConformanceAnnouncementStartup` (it existed only to order bean init after the holder was populated; the announcer needs only Flyway, which context refresh already guarantees). `ConformanceAnnouncer` keeps its digest logic and now calls the two-argument `publish` — its identity `SnapshotMutator.withVersion` mutation is deleted with the parameter. After this lands, `entitlement-service` has **no production import of `SnapshotMutator`** — that class belongs to the client's delta applier and to core tests. Verify with `grep -r SnapshotMutator entitlement-service/src/main` → empty.

## Tasks, in commit order

Each task is one commit, test-first per house rules, and the whole reactor must be green at every boundary (`./mvnw test` from `management/backend`).

- [ ] **T1 — beans.** Add the read transaction manager, `@Primary` the write one. Test: context loads; all existing service tests still pass (this alone proves the `@Primary` trap was handled).
- [ ] **T2 — prove the isolation mechanism.** `ReadTransactionIsolationTest`: via `TransactionTemplate(entitlementReadTransactionManager)`, open a read transaction, `SELECT MAX(version)`; on another thread commit a write that publishes a new version; re-read inside the still-open transaction → **unchanged**; after closing → new value visible. This is c31's load-bearing fact; nothing later merges without it.
- [ ] **T3 — DAO.** `DecisionReadDao` with the table in §2, each method's SQL copied from its named source. Tests per method against seeded rows, including the CLOSED-account invisibility case.
- [ ] **T4 — view + assembler.** `RecordBackedView`, `RecordViewAssembler` (all three entry points), `withoutOverride`. The keystone test is **three-way agreement**: seed a model with every value type, tiers, competing GRANTs/HOLDs, a retired capability, a CLOSED account; for every (account, capability) assert `resolve` and `explain` give identical results via point view, account view, and a `SnapshotAssembler.assembleFull()` snapshot. The view the service answers from and the snapshot the feed ships must be indistinguishable to the resolver — this is the invariant that replaces "same object, so trivially equal".
- [ ] **T5 — decision path.** `DecisionReadService`; `DecisionController` becomes thin; delete its `SnapshotHolder` field. All existing `DecisionController` tests pass unmodified (they assert HTTP behaviour, not the holder); add the c30 test: admin write returns `snapshotVersion` *N* → immediate `GET /v1/...?minSnapshotVersion=N` is 200 and reflects the change, never 409.
- [ ] **T6 — publisher.** New `publish(long, DeltaChange)`; rewrite `SnapshotPublisherTest`: returns the autoincrement version, throws outside a transaction *before* writing anything, rollback leaves no row. Update `ConformanceAnnouncer` in the same commit (same signature change).
- [ ] **T7 — admin write sites.** All 13 `publish` call sites per the table; mutation-response explanations via write-connection views. Existing admin service tests are the net — their response assertions must pass byte-identically.
- [ ] **T8 — admin read sites.** MetaController, the two list controllers, `AccountAdminService.get`, `PlanAdminService.preview`/`apply` token source, `GlobalExceptionHandler`.
- [ ] **T9 — feed.** §7 wholesale: feed read transactions, assembler off the startup path, pruner guard. `SnapshotFeedControllerTest` and `DeltaFeedServiceTest` pass; `ClientAgainstRealFeedTest` passes **untouched** — if it needed editing, the wire moved and the task is wrong.
- [ ] **T10 — delete the holder.** Remove `SnapshotHolder`, `SnapshotStartup`, the last imports. `grep -rn "SnapshotHolder\|snapshotHolder" entitlement-service/src/main` → empty. Full reactor green, frontend unit suite green, `npm run test:e2e` green (the SPA notices nothing, which is the point).
- [ ] **T11 — docs.** Amend 001's `plan.md`/`research.md` sentences that state "reads resolve against an in-memory snapshot" (research §8, plan "Load-bearing consequences"), `data-model.md:526`'s "none of these indexes is on a decision hot path", the write-pool validator's justification message (single writer stays right — SQLite says so — but the holder-read-modify-write reason is gone; a stale justification is how the constraint gets wrongly relaxed later), CLAUDE.md's architecture bullets, and a `DECISIONS.md` entry recording option A over the keep-the-holder alternative and why. 004's plan already measures `/v1` directly; add one line there noting the read path is now SQLite and that `read-pool-size` (currently 4 in `application.yaml`) is the first tuning knob if p99 misses.

## Risks, named

| Risk | Handling |
|---|---|
| **c31 becomes silent-failable** — four queries straddling a commit would mix states | T2 proves the WAL read-transaction mechanism in isolation; T4's eager-loading design means every view is built entirely inside one transaction by construction; no lazy JDBC in any view method |
| **c25–27 are reopened** — the in-memory path was their stated justification | Accepted knowingly: they were never evidenced, and 004 now measures the path that will actually ship. A ~50 MB database fully in page cache doing four indexed reads per decision is the bet; `read-pool-size` and (only if measurement demands) new indexes are the knobs. 004 must run **after** this lands — latency evidence is path-specific |
| **Read pool saturation** at 5k decisions/s | Pool is 4 today; at sub-millisecond per decision that is ~4k–8k/s of headroom, which is exactly the kind of arithmetic 004 exists to replace with a measurement. Not a Phase 0 concern; noted in T11 |
| **A mutation response explaining from the read pool by mistake** | It would silently explain the pre-write state (invisible in tests that don't race). Convention is structural: only `pointViewInWriteTxn` is reachable without the read transaction manager, and T7's response assertions catch the pre-write value |
| **`/full` streaming after the transaction closes** | Safe by design: the `Snapshot` object is assembled transactionally and immutable; the stream serialises memory, not the database |
| **Two transaction managers confuse future `@Transactional` authors** | The `@Primary` write manager preserves every existing annotation's meaning; `DecisionReadService` is the single home of the read annotation, and controllers stay annotation-free |

## What this hands 002 when the branch rebases

- `RecordBackedView` is where `knownOverrides()` gets its production implementation: the DAO grows `overridesAllForPair` (no `removed_at` filter), the view computes `OverrideStanding` per row at its `asOf`, and the invariant *filter(known, IN_FORCE) == liveOverrides* holds structurally because both lists derive from one query in one transaction.
- `RecordViewAssembler` grows an `asOf` parameter; point-in-time (Phase 5) becomes the same assembly fed by audit reconstruction instead of the live tables — same interface, same `Resolver.explain`, different date.
- The branch's `OverrideAdminService` conflicts (its `inForceNow`/`wasInForce` gates at the old holder call sites) resolve in one direction: keep the gates on **whether to publish**, delete the mutation halves — the shape after rebase is `publish-if-in-force`, nothing else.
- The midnight roll's job narrows to the feed: the service is exact at every instant by construction, so `WindowBoundaryRoller` exists to publish `override.created`/`override.removed` at boundaries for replicas, deriving its deltas from the V2 partial window indexes. The parent plan's "derive deltas by snapshot difference" mechanism is **withdrawn** — the phantom-event problem it solved is already solved at the call sites, and the roller derives from boundary queries.

## Definition of done

All backend tests green from a clean reactor run, frontend unit and e2e suites green, `ClientAgainstRealFeedTest` green and unmodified, no production reference to `SnapshotHolder`/`SnapshotStartup`/`SnapshotMutator` in `entitlement-service`, and the T11 documents amended. Criteria touched: c30 and c31 re-evidenced by the new tests; c25–27 unchanged in status (designed-for, awaiting 004 on this path); c41 untouched (`/admin/v1/meta` still serves the 60).
