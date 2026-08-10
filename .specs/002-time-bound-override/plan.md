# Implementation Plan: Time-bounded Overrides and Point-in-Time Answers (002)

**Branch**: `002-windows` (replayed from `002-time-bound-override`) | **Spec**: [`spec.md`](./spec.md) | **Revised**: 2026-08-10
**Status: complete — built and merged to `main` on 2026-08-10.** Every phase below is done. Two things came out differently from the plan and are recorded at the end under "What changed while building it"; read that before treating any section here as a description of the code.
**Builds on**: [`001-entitlement-service/`](../001-entitlement-service/), merged to `main`. Criterion references *(c14)* are **002's** criteria unless prefixed *v1*, in which case they point at [`001-entitlement-service/spec.md`](../001-entitlement-service/spec.md) §10.

> **Why this document was revised.** The 2026-08-09 draft was written against a `main` that has since moved twice over. Its Phase 0 — the read-path correction — has been **designed, built and merged** under a different shape than it predicted ([`plan-read-path.md`](./plan-read-path.md)); the SDK and the operator SPA that gated three of its phases are **built**; and the load-test harness two criteria depended on has been **withdrawn**, not deferred. Meanwhile the 002 branch has landed phases 1, 2, 3 and half of 5 against the *old* `main` and has not been rebased. This revision reconciles all three. Everything below describes the repository as it is on 2026-08-10.

## Summary

Overrides gain an optional start date and expiry date, and the operator checker gains a date so it can answer *what could this account do on 14 March*.

Both halves rest on a read-path posture that is no longer a proposal in this plan but a property of the codebase: **the management service resolves from SQLite; a snapshot is a thing a consuming client holds.** 001 built the snapshot to do two jobs — the management service's read model *and* the artifact replicas consume — and 002 is where that double duty stopped paying. It has been separated, `SnapshotHolder` and `SnapshotStartup` are deleted, and 002 inherits the benefit rather than having to earn it.

With that separation already made, 002 is markedly smaller than the original draft assumed:

- **A window is a SQL predicate.** The service's own answers begin and end at the exact instant the date says, with no scheduler involved in being correct. `WHERE (starts_on IS NULL OR starts_on <= :today) AND (expires_on IS NULL OR expires_on >= :today)` is the whole rule.
- **Naming not-in-force overrides in an explanation costs a query, not memory.** Under the old shape this feature would have forced an in-memory snapshot to retain every override ever created — the largest risk in the first draft. It is now a wider `WHERE` clause, and the risk is gone rather than mitigated.
- **Point-in-time stops being a separate mechanism.** The live path builds a view of *now* from SQLite (`RecordViewAssembler`); the historical path builds a view of *then*. Same shape, different `asOf`, same unchanged `Resolver.explain()`.
- **The midnight roll degrades from a correctness component to a publication one.** Its only job is telling replicas and writing the two audit rows. If it runs late, replicas lag — the service is still right.

What does not change is as important. **`Resolver.resolve()` is untouched, `resolverContract` stays at 1, the snapshot feed's shape is unchanged, and `entitlement-client` needs no release.** A boundary reaches replicas as the `override.created` and `override.removed` delta kinds that already exist. This contradicts 001, which named this feature as one that would bump `resolverContract` — in `contracts/README.md` and in `ResolverContract`'s own javadoc — and that prediction assumed replicas would evaluate windows themselves. They must not, and c14 is why: during an outage a product must go on honouring an override that has ended. A replica that could evaluate its own windows would lapse *correctly* while cut off, which is exactly what the fixed outage posture forbids. Keeping time out of the replica is the requirement, not an optimisation.

The second simplification comes from the date semantics. A start begins at the start of its day and an expiry runs to the end of its day, both on one clock, so **every boundary in the system falls at midnight** — the roll is one scheduled job a day plus a catch-up on startup.

## Baseline: `main` on 2026-08-10

277 Java files, 60 TypeScript files, nine SPA routes across the five §9 screens. Recorded suite: **524 backend** (core 105, client 168, service 251), **198 frontend unit**, **27 end-to-end**.

| Area | State | Bearing on 002 |
|---|---|---|
| `entitlement-core` — model, order, view, engine, conformance | Built | Phase 2 edits it. `EntitlementView` is an interface with two implementations now (`Snapshot`, `RecordBackedView`), which is the seam 002 uses |
| `entitlement-service` — `store/`, `audit/`, `snapshot/`, `error/`, `time/`, `api/`, `admin/`, `seed/`, `config/` | Built | Phases 3–5 extend these |
| **The SQLite read path** — `DecisionReadDao`, `RecordBackedView`, `RecordViewAssembler`, `entitlementReadTransactionManager` | **Built and merged** | Was this plan's Phase 0. See §0 |
| **`entitlement-client` (SDK)** | **Built** — replica, transport, sync loop, conformance gate, disk cache; 168 tests | **c14 is now demonstrable.** There is a replica to cut off |
| Operator SPA — nine routes, `TraceView`, `ValueEditor`, `CapabilityTree`, `SaveConfirmation`, `ErrorNotice` | Built | Phase 6 extends screens 3, 4 and 5 |
| **`e2e/`** — five screens against a real service on port 8099 | Built, 27 tests | Phase 6's deliverable is not done until it is covered here. Component tests are MSW-backed and cannot catch SPA/service disagreement |
| `seed/DemoDataSeeder` | Built, `entitlement.seed.enabled` off by default | Phase 7 extends it with a backdated trail |
| **Load-test harness** | **Withdrawn, not deferred** | v1 c25–27 were withdrawn on 2026-08-10 (`spec.md` §7, `future-spec.md` item 13). c15 is re-read accordingly — see Coverage |

Two consequences worth stating plainly. **Nothing in 002 is blocked on 001 any more** — every gate the first draft named has been discharged or withdrawn. And **the feed now has real consumers**, so the read-path change that once had a blast radius of zero no longer does: `ClientAgainstRealFeedTest` points a real client at the real running service and is the test that will notice if 002 moves the wire.

## Where 002 already stands

Branch `002-time-bound-override`, seven commits, forked at `68ca15c` — **before** the read-path work, the SDK, the e2e suite and the criteria withdrawal. It is behind `main` and has not been rebased.

| Phase | State on the branch |
|---|---|
| 1 — the clock | **Done.** `entitlement.clock.zone` (Eastern), a zoned `Clock`, `ServiceZone`'s boot assertion that midnight is unambiguous, `NoDirectClockAccessTest` |
| 2 — `entitlement-core` | **Done.** Windows on `AccountOverride`, `OverrideStanding`, `StandingOverride`, `knownOverrides` as a default method, the three `NOT_IN_FORCE_*` outcomes, `explain()` emitting them. `resolve()` untouched and pinned by test. `ResolverContract` stays at 1 |
| 3 — storage | **Done**, less the `audit_event` rebuild. `V2__override_windows.sql` adds the window columns and three indexes; repository readers for in-force, known-at-a-moment and both halves of a midnight; `WindowRules` |
| 5 — API, the half independent of point-in-time | **Done.** Window fields on override create, `standing` on the account view, `capability=` on the audit filter, `INVALID_WINDOW` |
| 4 — the midnight roll | Not started |
| 5 — point-in-time | Not started |
| 6 — UI | Not started |
| 7 — demonstration | Not started |

**The rebase is the first task, and it is not mechanical.** `main` has since rewritten or moved every one of these files: `SnapshotAssembler` (now walks `DecisionReadDao`), `AccountAdminService` and `OverrideAdminService` (now read through `RecordViewAssembler`), `AuditController`, `AccountDetailDto`, `ErrorCode`, `AccountOverrideRepository`, `HistoryRoute.tsx`, `types/domain.ts`. Three items in that list need judgement rather than conflict resolution, and each is called out in the Design below: the branch's window filtering went into `SnapshotAssembler`, which is now feed-only (§1); the branch's `V2` collides with `main`'s `V3` (§6); and the branch's one-publish-per-transaction reasoning was an invariant of the deleted holder and no longer applies (§3).

Two things the branch deliberately left undone, each still correct:

**The `audit_event` CHECK widening is deferred to Phase 4.** SQLite cannot alter a CHECK in place, and `snapshot_version.last_audit_seq` is a foreign key into `audit_event`, so the rebuild must run with `foreign_keys=OFF` — which is a no-op inside a transaction, and Flyway wraps migrations in one. See §6 for the resolution. Nothing writes `BEGIN`/`END` rows until the roller exists, so it lands with that code, where its verification can be reviewed on its own rather than buried in an additive migration.

**Explanations do not yet name not-in-force overrides in production.** The engine does (Phase 2, tested), but on the branch `explain()` read `knownOverrides` from a view that by design held only what was in force. On today's `main` the missing half is one method on `RecordBackedView` and one query on `RecordViewAssembler` — the reason this was left rather than built and unbuilt.

## Technical Context

**Language/Version**: unchanged — Java 21 across the three modules; TypeScript 5.x on Node 22 for the SPA.

**Primary Dependencies**: no new runtime dependency anywhere. Scheduling uses Spring's `@Scheduled`; dates use `java.time`. The branch's `NoDirectClockAccessTest` is a source scan, so no ArchUnit dependency is required either.

**Storage**: SQLite as before. `main` ships `V1__baseline.sql` and `V3__service_state.sql`. 002's migrations must be numbered **above** both — see §6, which is a correction to the branch and not a preference.

**The clock**: `time/ClockConfig` currently exposes `Clock.tick(Clock.systemUTC(), Duration.ofMillis(1))`. 002 makes it `Clock.tick(Clock.system(zone), Duration.ofMillis(1))` — the millisecond truncation is load-bearing and must survive the change, because `ClockConfigTest` and `MillisecondTimestampFormatTest` both pin it and the whole timestamp format depends on it. `Instant.now()` and `LocalDate.now()` stay forbidden outside the injected `Clock`, enforced by test rather than by review, because 002's definition of done requires criteria 11–13 to be demonstrated *by letting the clock reach a boundary* and a single stray `now()` makes a boundary test flaky rather than failing.

**Service clock**: `America/New_York`, everywhere, configured as `entitlement.clock.zone` for testability but not intended to vary. That is a behaviour change, not a refactor — any date derived from the clock moves from UTC to Eastern. Nothing in 001 derives a date from it (it is used for instants, and stored timestamps stay ISO-8601 UTC), so the change is safe now and would not be later. Eastern is the zone dates are *interpreted in*, never the zone anything is stored in.

Eastern observes daylight saving, and §3.1 depends on one property because of it: **midnight must be unambiguous.** It is — the gap and the overlap both fall at 02:00 — so `ZonedDateTime.of(date, MIDNIGHT, zone)` never lands in a skipped or doubled hour. `ServiceZone` asserts this for the configured zone at boot rather than assuming it, and refuses to start otherwise; that check is what catches a future move to a zone that *does* transition at midnight, which is the only way this design breaks. Two consequences must be honoured in code rather than discovered:

- **A day is not always 86,400 seconds.** The boundary scheduler computes the next midnight as `ZonedDateTime` arithmetic in the zone, never `previous + Duration.ofDays(1)`. The spring day is 23 hours and the autumn day 25; a fixed-duration schedule drifts an hour twice a year and eventually fires on the wrong side of a boundary.
- **The `Clock` must carry the zone**, so `LocalDate.now(clock)` is Eastern rather than the host default.

**Testing**: JUnit 5 + AssertJ, with three additions. Boundary tests drive a `MutableClock` across midnight and assert the value flips exactly once, on the right side, including on both daylight-saving days. The existing jqwik order-independence properties gain generated windows and generated evaluation instants. Point-in-time gets a **history-replay property**: apply a random sequence of writes with the clock advancing, then assert that asking *as at* each intermediate date reproduces the answer that was live at the time. That property is unusually strong here, because the live and historical paths are the same code — it compares two invocations of one implementation rather than a reconstruction against an original.

Three suites 002 must keep green that the first draft could not have named: `ClientAgainstRealFeedTest` (the wire has not moved), `RecordViewAssemblerAgreementTest` (the record-backed view and the snapshot answer identically), and `e2e/` (the SPA and the service still agree).

**Target Platform**: Cloud Run, one always-warm instance, `--max-instances=1`, SQLite replicated to GCS by Litestream (`DECISIONS.md` §9). Three consequences:

- **The instance must run with CPU always allocated (`--no-cpu-throttling`).** Cloud Run's default allocates CPU only while a request is in flight, so an in-process scheduled job is throttled to a stop between requests, and would fail *quietly* — nothing errors, replicas simply stop converging. This is no longer a correctness bug in the service, which lapses on read regardless; it is a c13 failure confined to replicas. The alternative, if CPU-always-allocated is unwanted, is Cloud Scheduler calling an idempotent admin endpoint. Either is fine; the default is not.
- **`--max-instances=1` makes the single writer an infrastructure guarantee**, so the roll cannot double-fire and needs no lock.
- **Instances are replaced** by revisions and platform maintenance, so the service can be down across a midnight without anyone deploying deliberately. Startup catch-up is a normal path, not an edge case. Litestream's documented final sync on SIGTERM was measured and **does not happen** under `-exec` supervision (`DECISIONS.md` §9), so the deployed database is whatever the last periodic sync captured — which matters to the migration undo in §6.

**Performance Goals**: a boundary reaches every replica within 60 s of midnight while the operator tool reflects it immediately (c13), and 95 of every 100 point-in-time answers return within 3 s (c29). v1's freshness and coherence promises — visible within 60 s, the 10 s reuse bound, read-your-writes, one coherent moment — are unaffected and are what c15 now means; the throughput rubric that used to sit beside them was withdrawn.

**Constraints**: point-in-time must never be reachable from `/v1` — it is an operator surface (§6.2), and exposing it product-side would invite it onto a request path. Conformance vectors must not gain window cases; replicas deliberately do not evaluate windows, so a window vector would make every one of them refuse to serve.

**Scale/Scope**: unchanged from 001, and no longer a risk. The heap question that dominated the first draft belonged to an in-memory snapshot the service no longer holds.

## Constitution Check

*GATE: must pass before implementation. Re-checked after design.*

No constitution is defined for this project, so this gate is not evaluated and blocks nothing — the finding 001 recorded. The design was checked instead against 002's 33 criteria; the coverage table is that check. It produced one accepted deviation and three recorded interpretations.

### Accepted deviations

| Rule departed from | Deviation | Authority / why | Consequence |
|---|---|---|---|
| `data-model.md` migration strategy: "`audit_event` may only ever gain columns … its content is a legal record" | The `audit_event` rebuild widens two CHECK constraints — `source` gains `CLOCK`, `action` gains `BEGIN` and `END` — via the twelve-step rebuild | c30 requires a beginning and an ending to be recorded *as made by the passage of time* and **as legible as any other entry**. Every alternative that avoids the rebuild records a lapse as something it is not: `action='REMOVE'` with `actor_id='clock'` reads as *the clock removed this override*, the exact confusion the criterion forbids | The rebuild copies a legal record, so the migration captures `COUNT(*)` and a checksum over `seq` and `occurred_at` before and after, aborts on mismatch, and logs both. **The undo is a Litestream restore to a timestamp**, not a local pre-migration copy — the instance disk is ephemeral, and the restore point must be recorded in the runbook *before* the revision ships. Done once; the rule stands afterwards |

The first draft carried a second deviation — resolving from SQLite rather than from an in-memory snapshot, against `research.md` §8. It is **settled and merged**, recorded with its alternatives in its plan of record, [`plan-read-path.md`](./plan-read-path.md). It is no longer 002's to justify. Its stated open question — that v1 c25–27 were the reason for the in-memory read path — closed the same day for an unrelated reason: those criteria were withdrawn.

### Recorded interpretations

| Spec text | Interpretation | Basis |
|---|---|---|
| c19, "names every override … that existed at the moment asked about", including removed ones | Every override **created at or before** the moment asked about, whatever its standing then — including removed ones. It does **not** include overrides created afterwards (c25). | The criterion says "existed", and a removed override existed. This is a `WHERE created_at <= :asOf` clause rather than a retention decision, so the cost that made this contentious in the first draft is gone. |
| c13, boundaries reaching products within 60 seconds | Boundaries take effect at midnight of the service clock, so this is a promise about **the roll completing and publishing within 60 s**, not about per-override timers. A start begins at `00:00:00` of its date; an expiry ends at `00:00:00` of the day *after* its date, the expiry day being inclusive (c4). | §3.1's whole-day semantics make every boundary a midnight. Finer granularity would contradict the choice of dates over moments. |
| c27, "a date of today returns the current answer" | Asking *as at* today resolves against the live view rather than reconstructing from history. | *As it stood at the end of that day* has no meaning before the day is over (§6.1). The two paths are the same code anyway, so this is a choice of `asOf`, not a branch. |

## Design

### 0. The read path 002 inherits

Built and merged; described in full in [`plan-read-path.md`](./plan-read-path.md). What 002 needs to know about it:

| Class | Role |
|---|---|
| `store/DecisionReadDao` | The only class bound to `entitlementReadJdbcClient`. Point queries copied verbatim from the repositories |
| `snapshot/RecordBackedView` | `EntitlementView` over rows rather than over a `Snapshot`. Two modes, `POINT` and `ACCOUNT_SLICE` |
| `snapshot/RecordViewAssembler` | The one place a `RecordBackedView` is built — `pointView`, `accountView`, and `pointViewInWriteTxn` for the one case the read pool cannot serve: a mutation response's explanation must reflect the write that has not committed yet |
| `snapshot/SnapshotAssembler` | Now **feed-only**. `assembleFull()` walks `DecisionReadDao` to build the artifact replicas consume, and nothing else reads it |
| `snapshot/SnapshotPublisher` | `publish(lastAuditSeq, delta)` inserts one `snapshot_version` row inside the caller's transaction and returns **the row's own autoincrement key**. There is no in-memory version counter and no deferred swap |

Four consequences that change 002's design as it was drafted:

- **Window evaluation has a new home.** `RecordViewAssembler` is where the service's own answers are assembled, so that is where the in-force predicate belongs — not in `SnapshotAssembler`, which the branch edited. `SnapshotAssembler` needs the predicate too, but for a different reason: to decide what a *replica* is told about. Two sites, two jobs. See §1.
- **The one-publish-per-transaction rule is gone.** It was an invariant of `SnapshotHolder`'s read-modify-write, and the holder is deleted. Two `publish()` calls in one transaction now produce two version rows with two autoincrement keys and two deltas, committing atomically. The roller may therefore do a whole night's transitions in one transaction, which the first draft ruled out.
- **`SnapshotMutator` is not on any read path.** Its only surviving service-side use is the hypothetical snapshot behind `PlanAdminService`'s plan-edit preview. 002 adds nothing to it.
- **One stale comment to fix while nearby.** `EntitlementDatabaseProperties` still justifies `write-pool-size = 1` as "SnapshotPublisher's read-modify-write of the in-memory snapshot depends on write serialization". The constraint is still right; the reason is now SQLite's single writer.

### 1. Where time is evaluated — two places, two jobs

| | Management service | Replicas |
|---|---|---|
| Reads | SQLite, per request | Their snapshot |
| Window evaluated | At query time, as a SQL predicate | Never — they hold no windows |
| Correct as of | The exact instant asked | The last published version |
| A boundary arrives | Immediately, by construction | Via the roll, within 60 s (c13) |

That asymmetry is the design, not a compromise. It gives c13's two halves — the tool immediate, products within a minute — and it gives c14, because a cut-off replica has no window to evaluate and no clock that matters. The cost is the predictable nightly divergence the spec records in §4 and its limitations table.

Three query sites carry the predicate, and they must not drift apart:

| Site | What it filters | Why |
|---|---|---|
| `RecordViewAssembler.assemblePoint` | Overrides in force at the asking instant, **plus** the wider known set for `knownOverrides` | The decision and its explanation (c10, c19) |
| `RecordViewAssembler.assembleAccountSlice` | The same, for every capability on the account | Screen 3's grouped list (c18) |
| `SnapshotAssembler.assembleFull` | Overrides in force at assembly | What a replica is told exists (c13) |

`RecordViewAssembler` deliberately repeats `DecisionReadDao`'s SQL rather than delegating, so that all three entry points can run against whichever `JdbcClient` they were handed. That duplication is now load-bearing in a new way: **the window predicate must be added to every copy, and `RecordViewAssemblerAgreementTest` is what will catch a missed one** — it asserts that the record-backed view and the snapshot answer identically, which is exactly false if one of them filters and the other does not.

Repository additions, all on `AccountOverrideRepository`:

- `findInForce(accountId, capabilityId, LocalDate asOf)` and `findInForceForAccount(accountId, LocalDate asOf)` — the live path
- `findKnown(accountId, capabilityId, Instant asOf)` — every override created at or before `asOf`, with `removed_at` and the window returned so standing can be computed (c19, c25)
- `findAllInForce(LocalDate asOf)` — replaces `findAllLive()` in the full-resync walk
- `findBoundaryTransitions(LocalDate)` — the overrides beginning or ending at one midnight

### 2. What `entitlement-core` gains

Small and additive. `resolve()` is not in this list. **Built on the branch**; carried through the rebase unchanged.

| Type | Change |
|---|---|
| `model/AccountOverride` | Two components: `Optional<LocalDate> startsOn`, `Optional<LocalDate> expiresOn`. Absent on the replica projection exactly as `reason`, `createdBy` and `createdAt` already are |
| `model/OverrideStanding` *(new enum)* | `IN_FORCE`, `PENDING`, `ENDED`, `REMOVED`, plus one pure static `of(AccountOverride, Optional<LocalDate> removedOn, LocalDate asOf)` — so the repository, the API and the UI share one rule rather than three |
| `model/StandingOverride` *(new record)* | `(AccountOverride override, OverrideStanding standing, Optional<LocalDate> notInForceSince)`. The pairing exists because an override is a statement of what was agreed while standing is a statement about a moment; folding one into the other would make the record's meaning depend on when it was read. `notInForceSince` is expiry-day-plus-one for `ENDED` and the removal date for `REMOVED`; empty for `IN_FORCE` and for `PENDING`, whose date is already `startsOn` |
| `view/EntitlementView` | One new method, `List<StandingOverride> knownOverrides(String accountExternalId, CapabilityKey key)`, with a default implementation wrapping the existing live-override reader as `IN_FORCE` — so `Snapshot`, every replica and every existing test fixture compile and behave unchanged. **`RecordBackedView` is the implementation that overrides it**, and supplying it the wider set is the last mile of c19–c21 |
| `engine/Outcome` | `NOT_IN_FORCE_PENDING`, `NOT_IN_FORCE_ENDED`, `NOT_IN_FORCE_REMOVED` |
| `engine/TraceEntry` | `Optional<LocalDate> startsOn`, `Optional<LocalDate> expiresOn`, `Optional<LocalDate> notInForceSince`, carried straight from the `StandingOverride` so the trace never recomputes standing |
| `engine/Resolver.explain()` | Reads `knownOverrides`, folds only `IN_FORCE` into the arithmetic exactly as today, and emits the rest as entries carrying a `NOT_IN_FORCE_*` outcome. Winner selection and every existing outcome are computed from the in-force subset alone, so no existing trace changes shape for an account with no windows. `evaluatedAt` keeps its present meaning — stamped on the `Decision`, never consulted in the arithmetic |
| `conformance/ResolverContract` | `VERSION` stays **1**. Its javadoc, which names time-bounded overrides as a reason to bump it, is wrong and is corrected in place — as is the matching claim in `contracts/README.md` line 107, which still stands uncorrected on `main` |

001's checked obligation that `resolve()` and `explain()` agree now carries the extra clause that adding not-in-force entries never moves the value.

`AccountAdminService.effectNow()` switches exhaustively over `Outcome`; the three new values must map to a not-in-force standing rather than an `effectNow`, and the compiler will point at it.

### 3. The midnight roll — publication only

One `@Scheduled` component, `WindowBoundaryRoller`.

- Fires shortly after midnight in the configured zone, computing the next fire as zoned arithmetic, plus a short safety interval so a missed fire is caught inside the 60 s budget.
- For each override beginning or ending, publishes one snapshot version carrying `DeltaChange.OverrideCreated` or `OverrideRemoved` and writes one `audit_event` row (`action='BEGIN'|'END'`, `source='CLOCK'`, `actor_kind='SYSTEM'`, `actor_id='clock'`). To a replica, a beginning and an ending are exactly a creation and a removal, which is why no new delta kind is needed.
- **A night is one transaction.** `SnapshotPublisher` now returns the database row's own autoincrement key, so N transitions in one transaction produce N version rows and N audit rows that commit together. The first draft's "one publish per transaction" was an invariant of the deleted `SnapshotHolder` and does not survive it. One transaction is the better shape: a night's boundaries either all land or none do, and no replica sees a half-rolled midnight.
- **Catches up on startup**, applying every boundary passed while the service was down. Its work is derived from the database — which overrides are in force versus which the last published version accounted for — never from in-process memory of when it last ran.
- **Idempotent**, because the safety interval guarantees it will sometimes run twice.

Because boundaries are midnights, the work is bounded by the overrides whose start or expiry equals the rolling date — an indexed query, not a scan. Two existing bounds interact with it: `DeltaFeedService` caps a delta response at 5,000 change rows, and `SnapshotVersionPruner` drops delta rows past `entitlement.snapshot.delta-retention` (7 d). A replica that misses a night by more than a week full-resyncs, which is correct and already tested; a replica that misses an unusually large night pages or full-resyncs, likewise.

The pending-override asymmetry the branch already implements stays: **a pending override is stored and audited but not published**, and removing an override that was never in force publishes nothing. Both are phantom-event guards, and both are asserted by checking that the snapshot version does not move. This is a deliberate, documented exception to CLAUDE.md's "every mutating admin path ends in `publish()`" — there is no model change for a replica to learn about, and manufacturing a version for one would put a fictional moment in the feed.

### 4. Answering about the past

`AsAtViewAssembler` builds an `EntitlementView` for a past moment and hands it to the unchanged `Resolver.explain()`. It is a sibling of `RecordViewAssembler`, not a special mechanism, and should read through the same read pool inside the same kind of read-only transaction.

**A date resolves to an audit sequence first.** `asAtSeq = MAX(seq) WHERE occurred_at < startOfDay(date + 1)`. Every later lookup is "the latest entry at or before `asAtSeq`", which gives a past answer the same one-coherent-moment property `snapshotVersion` gives a live one (v1 c31).

| Fact as at the date | Source |
|---|---|
| Which plan the account was on | `audit_event`, `entity_type IN ('ACCOUNT','ACCOUNT_PLAN')`, `account_id`, latest ≤ `asAtSeq`, taking `after_json`. No entry at all ⇒ the account did not exist (c26) |
| What that plan set for the capability | `audit_event`, `entity_type='PLAN_ENTITLEMENT'`, `plan_id`, `capability_id`, latest ≤ `asAtSeq`. Absent ⇒ the plan was silent, so the capability default applies |
| The capability's default and off-value | `audit_event`, `entity_type='CAPABILITY'`, `capability_id`, latest ≤ `asAtSeq` |
| Which overrides existed, and their standing | `account_override` directly — `findKnown(account, capability, asOf)`. No history needed; this is the half the windows make free |

**Refusals, never guesses (c26, c27).** A date before the account's first audit entry, a date beyond the history, and a future date each return their own problem type and never a value, and never today's value. *Beyond the history* needs the most care: it must be distinguishable from *the account existed and nothing was set*, which is why the assembler checks for an establishing entry rather than inferring from an empty result.

**Retired capabilities (c28).** The assembler builds the capability as it stood, so one retired since the date resolves normally and the response carries `capabilityRetiredSince` — deliberately unlike asking about a retired capability today, which stays the v1 `entitlement/retired-capability` error.

**One reconstruction hazard the read-path change introduced.** `after_json` is written by `AuditRecorder` at the time of the mutation, so a past answer is only as faithful as the payloads 001 chose to record. Phase 5's first task is to confirm, against the real seeded trail, that the four lookups above can actually be satisfied from what is stored — and to say so if they cannot, rather than to widen the query until something comes back.

### 5. Retention is already satisfied — by 001's triggers

c32 (seven years) and c33 (establishing entries outliving it) need **no pruning logic, because 001 has none and its schema forbids adding any**: `trg_audit_no_delete` raises on any `DELETE` against `audit_event`. Retention is unbounded and enforced by the engine rather than by a policy someone must remember.

002's work is therefore evidential: raise the documented floor from twenty-four months to seven years, and add a test asserting a `DELETE` is refused — turning a schema property into a demonstrated criterion. One obligation is recorded for the future rather than built: **any pruning job ever added must exclude the most recent establishing entry per `(entity_type, entity_id, capability_id)`**, and that test is what will fail if someone forgets.

`SnapshotVersionPruner` is the counter-example that makes this worth stating twice: `snapshot_version` is pruned on a seven-day horizon and `audit_event` is never pruned at all. The two must never be given one retention story.

### 6. Storage — and the migration numbers must change

**The branch's `V2__override_windows.sql` cannot ship as `V2`.** `main` carries `V1` and `V3`; the deployed database has both applied and is restored from GCS on every cold start, so it will never re-run history. Flyway's `out-of-order` defaults to false and `validate-on-migrate` to true, and `application.yaml` sets neither — so a `V2` appearing after `V3` has been applied is a hard startup failure on the deployed instance, not a warning. Renumber:

| Was | Becomes | Contents |
|---|---|---|
| `V2__override_windows.sql` | **`V4__override_windows.sql`** | The window columns and three indexes |
| *(deferred, Phase 4)* | **`V5__audit_window_transitions`** | The `audit_event` rebuild |

`V3`'s own header comment explains the gap by saying `V2` is reserved for this branch, and it will be wrong once the renumber lands. **Do not edit it.** Flyway records a checksum per migration file; changing a comment in an applied migration fails validation on the deployed database exactly as an out-of-order version would. The correction belongs in `CLAUDE.md`'s "Conventions and traps" section, which carries the same claim and is not checksummed.

```sql
ALTER TABLE account_override ADD COLUMN starts_on  TEXT;   -- 'YYYY-MM-DD', NULL = from creation
ALTER TABLE account_override ADD COLUMN expires_on TEXT;   -- 'YYYY-MM-DD', NULL = open-ended
```

SQLite cannot add a CHECK to an existing table, so `starts_on <= expires_on` is enforced in `WindowRules` and covered by a test. The no-back-dating rules (c7) live there regardless, since they compare against the clock and are not expressible as a column constraint at all.

```sql
CREATE INDEX ix_override_window_start ON account_override(starts_on)  WHERE starts_on  IS NOT NULL;
CREATE INDEX ix_override_window_end   ON account_override(expires_on) WHERE expires_on IS NOT NULL;
CREATE INDEX ix_audit_capability      ON audit_event(capability_id, seq DESC);
```

The third is a gap 002 found in v1: `audit_event` carries `capability_id` and V1 indexes by account, plan, actor and time — the three filters §8 required — but not by capability, which is exactly what two of the four point-in-time lookups need and what c31 exposes to operators.

Soft removal (c16, c17) needs **no schema work**: `account_override` already carries `removed_at`, `removed_by`, `removed_reason`, and `AccountOverrideRepository.remove()` already sets them. 002 surfaces what is already recorded.

**The `audit_event` rebuild (`V5`) must run outside a transaction.** `jdbcUrl()` sets `foreign_keys=on`, and `snapshot_version.last_audit_seq` references `audit_event(seq)`, so the twelve-step rebuild needs `foreign_keys=OFF` — which SQLite treats as a no-op inside a transaction, and Flyway wraps SQL migrations in one. The resolution is a **Java-based migration whose `canExecuteInTransaction()` returns false**, which is also the natural home for the count-and-checksum verification the deviation requires. It widens `source` to include `CLOCK`, widens `action` to include `BEGIN` and `END`, adds nullable `window_transition TEXT`, and recreates both append-only triggers. `AuditEntry.action` is already a `String`, so the CHECK is the only gate to widen — no enum to extend.

### 7. Contracts

Everything is additive, so `/v1` keeps its additive-only promise and no version is forked.

| Contract | Change |
|---|---|
| `decision-api.md` | `trace.grants[]` and `trace.holds[]` may carry entries whose `outcome` is `NOT_IN_FORCE_*`, with `startsOn`, `expiresOn`, `notInForceSince`. `grantStep.why` gains `NO_GRANTS_IN_FORCE`. **No `asAt` parameter** — the past is an operator surface (§6.2) |
| `snapshot-feed.md` | **Unchanged.** `format` 1, `resolverContract` 1 |
| `java-client-sdk.md` | **Unchanged.** No SDK release is required by this feature — and now that the SDK exists, `ClientAgainstRealFeedTest` is what proves it |
| `README.md` | The "Versioning and compatibility" note predicting a `resolverContract` bump for time-bounded overrides is **wrong and corrected in place**, as is `ResolverContract`'s javadoc |
| `admin-api.md` | `POST …/overrides` accepts `startsOn`, `expiresOn`; `entitlement/invalid-window` (422) covers start-after-expiry, wholly-past windows and back-dated starts. `GET …/accounts/{external}` overrides gain `startsOn`, `expiresOn`, `standing`; `effectNow` stays scoped to in-force overrides. `GET /admin/v1/check` accepts `asAt=YYYY-MM-DD` — note it now has **two lookup modes**, `override=` as well as `account`+`capability`, and both delegate to `DecisionController.single`, so `asAt` must thread through the delegation rather than fork it, and the `no-store` wrapper must survive. `GET /admin/v1/audit` accepts `capability=`, which also means adding `capabilityId` to `AuditEventFilter`. Four `ErrorCode` constants: `INVALID_WINDOW` (built), `BEFORE_ACCOUNT_EXISTED`, `BEYOND_HISTORY`, `FUTURE_DATE` |
| `ui-screens.md` | Screens 3, 4 and 5 change; 1 and 2 do not |

### 8. UI

**Screen 3, account view** (`AccountDetailRoute`) — the override form gains two optional date fields and, beneath them, the sentence §3.1 requires: *"In force from 1 October to 31 December inclusive."* Blank stays the fastest path through the form. The override list groups by standing, in force first and prominent; pending, ended and removed present but visibly not counting, with removed collapsed by default.

**Screen 4, checker** (`CheckerRoute`) — an optional date. When set, a persistent banner states the screen is showing the past and names it, with the current answer one click away. `<TraceView>` renders not-in-force entries dimmed with their reason — *ended 30 June*, *begins 1 October*, *removed 12 May* — and keeps 001's rule unchanged: it maps enum values to labels and derives nothing. `TraceCandidate.outcome` is typed `string` in `types/domain.ts`, so new outcomes need new labels, not a type change.

**Screen 5, change history** (`HistoryRoute`) — a capability filter, and rows for beginnings and endings that read as such rather than as an operator's act. The route already renders `event.capability`; it has no filter control yet.

Every screen showing a date names the clock (c5).

Four constraints from 001's hard-won UI rules, none optional:

- **The date fields are on a mutation form**, so `reset()` must be guarded by `if (!mutation.isPending)` and the `<ErrorNotice>` must live outside any collapsible. One keystroke during a slow save was enough to lose a write's outcome the last time.
- **A successful write must render something** even when `GET /admin/v1/meta` fails; `SaveConfirmation`'s `seconds` stays optional.
- **The service computes, the SPA renders.** Standing is `OverrideStanding`, computed once in core and carried on the wire. The SPA must not derive *ended* from comparing `expiresOn` to the browser's clock — that is a second implementation of a date rule, in a different time zone, in the least defensible place.
- **Component tests cannot close this phase.** They are MSW-backed and cannot catch SPA/service disagreement; `e2e/operator-screens.spec.ts` is where the window form, the grouped list, the past-date banner and the capability filter are actually proved.

**Interaction to settle before Phase 6 starts.** Spec 003 (plain-English checker) also changes screen 4, and its work is live on `worktree-entitlement-ask-nlp`. Two features adding controls to the same screen should agree on the layout once rather than merge twice.

## Coverage

| Criteria | Where |
|---|---|
| 1–4 windows saved, the three not-counting states, inclusive expiry day | Phase 2 (rule), Phase 3 (storage), Phase 5 (over HTTP) |
| 5–6 one named clock, meaning shown in words before saving | Phase 1 (zone, validated at boot), **Phase 6** (named and phrased on screen) |
| 7 the three refusals: start after expiry, wholly past, back-dated start | Phase 3 (rules), Phase 5 (`entitlement/invalid-window`) |
| 8–9 no edit; extension by overlap under the unchanged rule | Phase 2 |
| 10–12 only in-force overrides count; a start raises and an end releases with nobody acting | Phase 2 (rule), Phase 3 (SQL predicate), Phase 4 (the clock reaching the boundary) |
| 13 tool immediate, products within 60 s | Phase 3 (immediate half, by construction), Phase 4 (published half), evidenced **Phase 7** |
| 14 a cut-off replica goes on honouring an ended override | Phase 4, evidenced **Phase 7** — no longer blocked; the SDK exists |
| 15 every v1 speed and freshness demonstration unchanged | **Phase 7**. Now means v1 c28–31 — freshness, the reuse bound, read-your-writes, one coherent moment. v1 c25–27 were withdrawn, so there is no throughput run to repeat and none is owed |
| 16–17 ended overrides never cleared; removal retains the record | Phase 3 — already true in 001's schema; surfaced and tested |
| 18 account view grouped by standing | Phase 6 |
| 19–21 the explanation names not-in-force overrides, and is still the decision's own | Phase 2 (produced), Phase 3 (`RecordBackedView.knownOverrides` supplies the wider set), Phase 5 (carried), Phase 6 (rendered) |
| 22–25 past answers reflect plan, plan value, default and overrides as they stood; nothing created later appears | Phase 5 |
| 26–28 before-account, beyond-history, future, today, retired-since | Phase 5 |
| 29 95 of every 100 past answers within 3 s | Phase 5 (built), **Phase 7** (measured — at the service layer against the seeded trail, since there is no load harness and none is owed) |
| 30 beginnings and endings in history, made by the passage of time | Phase 4 (schema and written), Phase 6 (read) |
| 31 history filterable by capability | Phase 3 (index), Phase 5 (route), Phase 6 (screen) |
| 32–33 seven-year retention; establishing entries outlive it | Phase 3 |

## Phases

Nothing is gated on 001 any more. The ordering is a dependency ordering, and the first item is debt.

0. **Rebase onto `main`.** Carry phases 1, 2, 3 and the built half of 5 forward over the read-path change. Move the branch's window filtering from `SnapshotAssembler` into `RecordViewAssembler` and keep it in both (§1); renumber `V2` to `V4` (§6); drop the one-publish-per-transaction assumption (§3). *Deliverable: the branch's 002 tests and `main`'s 524 backend / 198 frontend / 27 e2e all pass together, on one branch.*
1. **The clock.** *Built.* `ClockConfig` to Eastern keeping the millisecond truncation; `ServiceZone`'s boot assertion that midnight is unambiguous; `NoDirectClockAccessTest`.
2. **`entitlement-core`: windows in the model, standing in the explanation.** *Built.* Remaining after the rebase: correct `ResolverContract`'s javadoc and `contracts/README.md` line 107.
3. **Storage, validation, and the live predicate.** *Built except the last item.* `V4`; the new repository readers; the three c7 validations; `WindowRules`. **Outstanding: `RecordBackedView.knownOverrides` and the assembler queries behind it** — the last mile of c19–c21. *Deliverable: an override begins and ends at exactly the right instant in the service, and an explanation names the one that ended, with no scheduler running.*
4. **The midnight roll.** `V5`'s out-of-transaction `audit_event` rebuild with its count-and-checksum verification; `WindowBoundaryRoller` with zoned next-midnight arithmetic, a night per transaction, startup catch-up and idempotency; both daylight-saving-day tests. *Deliverable: a real replica sees `override.removed` within the budget after a driven midnight, including on a 23-hour and a 25-hour day.*
5. **Point-in-time and the rest of the API.** `AsAtViewAssembler`, `asAtSeq` resolution, the four lookups, the refusal cases, `capabilityRetiredSince`; `asAt` threaded through both checker lookup modes; three new `ErrorCode` constants. *Deliverable: a past date returns the answer that was live then, evidenced by the history-replay property.*
6. **UI.** Screens 3, 4 and 5, with e2e coverage. *Deliverable: an operator can set a window, see why access dropped, and ask about last month — proved against a real service, not a handler.*
7. **Demonstration.** A boundary scenario for c13, an SDK-cutoff scenario for c14, a point-in-time timing measurement for c29, and v1 c28–31 re-demonstrated for c15.

   The seeder needs more from 002 than data. `DECISIONS.md` §9 requires the demo to seed **change history as well as data**, ending at the state the demo opens in — and for 002 that is the precondition for demonstrating anything: c22–c29 are unanswerable against a database whose audit trail begins at deployment. `DemoDataSeeder` must lay down a coherent backdated history — plan edits, reassignments, capability default changes, and overrides whose windows have begun, ended and been removed — so that asking *as at* three months ago returns something both correct and interesting.

   One thing to state plainly so nobody reads it as a defect: **the seeder writes backdated rows directly to the database, which the API refuses to do (c7).** That is not a contradiction. c7 governs what an operator may assert through the service; the seeder manufactures a fictional past for a demonstration and is the only component permitted to. It must be excluded from the no-back-dating tests deliberately rather than by accident. Note also that `e2e/start-backend.sh` runs with `entitlement.seed.enabled=true` and wipes its database on every launch, so a richer seeder changes what the e2e suite sees — several of its assertions describe the seeded account's *resolved* state.

## Risks

| Risk | Assessment |
|---|---|
| **The rebase, not the remaining features** | Seven commits written against a `main` that has since rewritten every file they touch. The three judgement calls are named in "Where 002 already stands"; the mechanical conflicts are noise by comparison. `RecordViewAssemblerAgreementTest` and `ClientAgainstRealFeedTest` are the two tests most likely to catch a bad merge, and neither existed when the branch was cut |
| **The migration numbers** | Shipping `V2` after `V3` is applied is a hard startup failure on the deployed instance, and the deployed instance restores its database from GCS rather than rebuilding it. Renumber to `V4`/`V5` before anything else is merged; do not edit `V3` to match, because its checksum is recorded |
| **The `audit_event` rebuild touches a legal record** | Mitigated by count-and-checksum verification inside a Java migration that runs outside Flyway's transaction, and undone by a Litestream restore to a recorded timestamp. Litestream's SIGTERM flush was measured and does not happen, so the restore point is the last periodic sync, not the moment of deploy — record it deliberately |
| **The window predicate lands in two of three query sites** | `RecordViewAssembler` repeats `DecisionReadDao`'s SQL on purpose, so the predicate has three copies. A missed one makes the service and the feed disagree about who has access — silently, in one direction only |
| **The two daylight-saving days** | Silent and twice-yearly, the worst combination for finding late. Mitigated by zoned next-boundary arithmetic and fixed-date tests across the March and November transitions |
| **Cloud Run CPU throttling stopping the roller** | Fails quietly. No longer a correctness bug — the service stays right — but replicas stop converging and c13's second half fails. Needs `--no-cpu-throttling` or a Cloud Scheduler trigger, and an alert on the age of the last published version |
| **A window case added to the conformance vectors** | Would make every replica refuse to serve, since their engines deliberately do not evaluate windows. Needs a comment in `ConformanceVector.spec5WorkedExamples()` explaining why, and an assertion in `ConformanceCheckTest` — which already tests the set's breadth — that no vector carries a window. Note that adding a vector now announces itself as a `conformance.changed` delta and must **not** bump `resolverContract` |
| **The past is only as faithful as `after_json`** | Reconstruction reads payloads 001 wrote for a different purpose. Phase 5 confirms the four lookups can be satisfied from what is stored, against the real seeded trail, before building on the assumption |
| **The demo's seeded history is the whole evidence base for the point-in-time half** | Without a matching audit trail, c22–c29 are undemonstrable and the feature will look broken rather than unseeded. Phase 7 treats the trail as the deliverable, not a by-product |
| **Someone outside Eastern is surprised by a boundary** | Accepted, recorded in the spec's limitations. Naming the clock wherever a date appears is the mitigation, and Phase 6 must not treat it as decoration |

## What changed while building it

Recorded because the plan above was written before the work and a reader should not have to diff it against the code.

| Planned | Built | Why |
|---|---|---|
| `V4__override_windows.sql` plus a `V5` SQL migration for the `audit_event` rebuild | `V5` is a **Java** migration reaching Flyway as a `@Component` | The rebuild needs `PRAGMA foreign_keys=OFF`, which SQLite ignores inside a transaction and Flyway wraps SQL migrations in one. Location scanning did not find it; Boot collects `JavaMigration` beans, which does |
| The window predicate in `RecordViewAssembler` and `SnapshotAssembler` | Also in `DecisionReadDao`, and defined once as `AccountOverrideRepository.IN_FORCE` | Three query sites, not two — `RecordViewAssembler` repeats the DAO's SQL so it can run against either pool. Three copies of a predicate is a predicate that will differ in one of them |
| `grantStep.why` gains `NO_GRANTS_IN_FORCE` | That, **and** the SPA states it even when candidates are listed | The absence sentence was rendered only for an empty list, so a trace full of dimmed rows carried no statement at all. c20 asks the explanation alone to be enough |
| c18 groups an account's overrides by standing | The account view had to widen from live overrides to **all** of them | A removed override cannot be shown in a grouping that never fetched it |
| A backdated demo history for c22–c29 | **Not built.** The seeder gives each standing an override at today's date instead | `asAtSeq` is `MAX(seq)` below a boundary, so seq order and `occurred_at` order must agree. Appending a backdated trail to an existing database gives early rows high sequences and makes every past answer silently return today's. It has to be written in time order or not at all — `AuditTrailOrderingTest` now states the invariant. Point-in-time therefore reaches back only as far as the deployment's own trail, which is honest but thinner than the demo deserves |
| c29 measured by a load harness | Measured at the service layer, p95 over 100 answers | There is no load harness and none is owed — v1's throughput rubric was withdrawn (`future-spec.md` item 13) |

Two demonstrations that were blocked when this plan was first written are now real: **c14** has a test that cuts a live SDK replica off and watches it go on honouring an ended override, and **c13**'s published half is driven by moving a clock across midnight, including both daylight-saving days.

## Complexity Tracking

No constitution is defined, so there are no violations to justify. The one departure from 001's own decisions is recorded under "Accepted deviations", where it will not be mistaken for a waived principle.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| — | — | — |
