# Implementation Plan: Time-bounded Overrides and Point-in-Time Answers (002)

**Branch**: `002-time-bound-override` | **Date**: 2026-08-09 | **Spec**: [`spec.md`](./spec.md)
**Builds on**: [`001-entitlement-service/`](../001-entitlement-service/). Criterion references *(c14)* are **002's** criteria unless prefixed *v1*, in which case they point at [`001-entitlement-service/spec.md`](../001-entitlement-service/spec.md) §10.

**Baseline**: branch `worktree-entitlement-service-api-layer`, **not `main`** — 001's service work lives there, having been assembled from sibling task worktrees merged back as each review cleared. Modules are under `management/backend/{entitlement-core,entitlement-service,entitlement-client}` and `management/frontend/management-ui`; 001's `plan.md` shows them at the repository root, which is stale and should be corrected there rather than followed here.

What 001 has actually landed, and therefore what each 002 phase can stand on:

| 001 area | State | Bearing on 002 |
|---|---|---|
| `entitlement-core` — model, order, view, engine, conformance | Built | Phase 1 edits it directly |
| `entitlement-service` — `store/`, `audit/`, `snapshot/` (`SnapshotAssembler`, `SnapshotHolder`, `SnapshotPublisher`, `SnapshotStartup`, `DeltaFeedService`), `error/ErrorCode`, `time/ClockConfig` | Built | Phases 2, 3 and 5 extend these; the class names this plan uses are the real ones |
| `api/` — `DecisionController`, `SnapshotFeedController` | Built | Phase 1's trace additions surface here with no new route |
| `admin/` — **only `CapabilityAdminController`** | Partial | **Phase 4 is blocked**: the plan, account, override, checker and audit admin controllers do not exist yet, and 002 adds fields and parameters to routes 001 has not written |
| `entitlement-client` (SDK) | Not started | **c14's outage demonstration is blocked** — there is no replica to cut off |
| Operator SPA | Scaffold only | **Phase 6 is blocked** — there are no screens to extend |
| `seed/`, load-test harness | Not started | **Phase 7 is blocked** — c15 needs 001's harness, and c22–c29 need seeded history |

Phases 1, 2, 3 and 5 can proceed against this baseline today. Phases 4, 6 and 7 wait on the 001 work named above, and the sequencing section below reflects that rather than assuming a finished v1.

## Summary

Overrides gain an optional start date and expiry date, and the operator checker gains a date so it can answer *what could this account do on 14 March*. Both halves rest on one structural idea, and it is the same idea 001 already uses: **the engine never learns about time — a view is built for a moment, and the engine resolves against it.**

001 established that a decision resolves against an immutable `Snapshot` representing one coherent moment. 002 makes the *choice of moment* explicit in two directions. Forwards: the published snapshot now contains only the overrides in force at publication, so an override that has not begun or has ended is simply not in the snapshot — indistinguishable, to everything downstream, from one that does not exist yet or has been removed. Backwards: a new `AsAtViewAssembler` builds an `EntitlementView` for a past moment out of the audit trail and the override windows, and hands it to the very same `Resolver.explain()`.

The consequence is that **`Resolver.resolve()` does not change at all, `resolverContract` stays at 1, the snapshot feed's shape is unchanged, and `entitlement-client` is not touched.** A window boundary reaches replicas as the `override.created` and `override.removed` delta kinds that already exist. This contradicts what 001 predicted — `contracts/README.md` names time-bounded overrides as a change that would bump `resolverContract` — and the prediction was made on the assumption that replicas would evaluate windows themselves. They must not, and the spec is why: c14 requires that during an outage a product goes on honouring an override that has ended. A replica that could evaluate its own windows would lapse correctly while cut off, which is precisely the behaviour the fixed outage posture forbids. Keeping time out of the replica is not an optimisation; it is the requirement.

The second structural idea is smaller and comes from the date semantics. A start begins at the start of its day and an expiry runs to the end of its day, both on one clock, so **every window boundary in the system falls at midnight**. The "clock moved" problem that `future-spec` warned would need designing turns out to be one scheduled job a day, plus a catch-up on startup for the case where the service was down over a midnight.

Explanations grow. The checker must now say *there was a GRANT of 200 and it ended on 30 June* rather than *no GRANT in force*, which means `explain()` needs the overrides that `resolve()` filtered out. They travel in the management service's own snapshot, never on the feed, which keeps them on the same side of the line as reason text and authorship.

## Technical Context

**Language/Version**: unchanged from 001 — Java 21 for `entitlement-core`, `entitlement-service`, `entitlement-client`; TypeScript 5.x on Node 22 for the operator SPA.

**Primary Dependencies**: no new dependency in any module. Scheduling uses Spring's `@Scheduled` (already on the classpath via `spring-boot-starter`); dates use `java.time` only.

**Storage**: SQLite as before. One Flyway migration, `V2__override_windows.sql`, adds two columns to `account_override`, one column to `audit_event`, widens two `audit_event` CHECK constraints, and adds three indexes. The CHECK widening requires the twelve-step table rebuild and is a recorded deviation from 001's migration rules — see "Accepted deviations" below.

**The clock is a dependency**: 001 already ships the bean — `time/ClockConfig` exposes `Clock.systemUTC()` — so 002 changes one line rather than introducing plumbing, and then enforces its use. `Instant.now()` / `LocalDate.now()` are forbidden outside the injected `Clock`. This is not tidiness — 002's definition of done requires criteria 11, 12 and 13 to be demonstrated *by letting the clock reach a boundary*, so tests must be able to move it. A build-time check (ArchUnit rule or a grep in the verify phase) enforces the ban, because a single direct `now()` call makes a boundary test flaky rather than failing.

**Service clock**: `America/New_York`, the whole service, everywhere — configured as `entitlement.clock.zone` for testability but not intended to vary. Concretely, `ClockConfig` becomes `Clock.system(ZoneId.of(zone))` instead of `Clock.systemUTC()`. That is a behaviour change, not a refactor: any date derived from the clock moves from UTC to Eastern. Nothing in 001 derives a date from it today — the clock is used for instants, and stored timestamps stay ISO-8601 **UTC** as 001 has them — so the change is safe *now* and would not be later. Eastern is the zone dates are *interpreted in*, never the zone anything is stored in.

Eastern observes daylight saving, and §3.1 depends on one property because of it: **midnight must be unambiguous**. It is — the gap and the overlap both fall at 02:00 — so `LocalDate → ZonedDateTime.of(date, MIDNIGHT, zone)` never lands in a skipped or doubled hour, and every date boundary names exactly one instant. Startup validates this rather than assuming it: for the configured zone, assert no DST transition occurs at 00:00 on any date within the retention window, and refuse to boot otherwise. That check is what would catch a future zone change to somewhere that *does* transition at midnight, which is the only way this design breaks.

Two consequences fall out and must be honoured in code rather than discovered:

- **A day is not always 86,400 seconds.** The boundary scheduler must compute the next midnight as `ZonedDateTime` arithmetic in the zone, never as `previous + Duration.ofDays(1)`. On the spring-forward day the interval is 23 hours and on the autumn day 25; a fixed-duration schedule drifts an hour twice a year and eventually fires on the wrong side of a boundary.
- **`LocalDate.now()` needs the zone, not the host's default.** The injected `Clock` therefore carries the zone (`Clock.system(easternZone)`), and the build-time ban below extends to zone-less `LocalDate.now()` as well as `Instant.now()`.

**Testing**: JUnit 5 + AssertJ as before, with three additions. Boundary tests drive a `MutableClock` across midnight and assert the value flips exactly once, on the right side. The existing jqwik order-independence properties are extended to generate windows and evaluate at generated instants, asserting that permuting the override list still changes nothing. Point-in-time gets a "history replay" property: apply a random sequence of writes with the clock advancing, then assert that asking *as at* each intermediate date reproduces the answer that was live at the time — the strongest available statement that reconstruction and reality agree.

**Target Platform**: Cloud Run, one always-warm instance, `--max-instances=1`, SQLite replicated to GCS by Litestream (`DECISIONS.md` §9). Three consequences for this feature, one of them a silent failure if missed:

- **The instance must run with CPU always allocated (`--no-cpu-throttling`).** Cloud Run's default allocates CPU only while a request is in flight, so an in-process scheduled job is throttled to a stop between requests. The midnight roll would then fire late, erratically, or not at all — and it would fail *quietly*, since nothing errors; overrides would simply go on applying past their expiry until the next request happened to wake the instance. This is the single most likely way 002 breaks in production while passing every test. If CPU-always-allocated is not wanted, the alternative is Cloud Scheduler calling an admin endpoint, and the roll must then be an idempotent HTTP-triggered operation rather than an `@Scheduled` method. Either is fine; the default is not.
- **`--max-instances=1` turns the no-leader-election argument into an infrastructure guarantee.** 001 relied on SQLite's single writer as a convention; the deployment now enforces it, so the roll cannot double-fire across instances and needs no lock.
- **Instances are replaced.** Revisions and platform maintenance restart the service, so it can be down across a midnight without anyone deploying deliberately. Startup catch-up is therefore not an edge case, and the roll must derive its work from *what the last published snapshot contains* rather than from any in-process memory of when it last ran — which the difference-based delta derivation in §1 already gives.

**Project Type**: unchanged multi-module Maven reactor plus the React SPA.

**Performance Goals**: 001's targets are unchanged and must be re-demonstrated unchanged (c15). Two new ones: a window boundary reaches every replica within 60 s of midnight (c13), and 95 of every 100 point-in-time answers return within 3 s (c29).

**Constraints**: point-in-time is an operator surface only and must never be reachable from `/v1` — a past answer costs SQLite reads, and putting it on the product-facing path would put the decision path back on the database, undoing 001's central decision. Conformance vectors must not gain window cases; they are evaluated by replicas whose engines deliberately know nothing about windows.

**Scale/Scope**: the management snapshot now retains overrides that are pending, ended and removed, because the explanation names them (c19). At 001's assumed ~50,000 live overrides, with churn over the seven-year retention, budget ~250,000–500,000 override records at ~200 B ≈ 50–100 MB on top of 001's ~50 MB, so ~150 MB steady-state and ~300 MB during the double-buffered swap. Comfortable in the 1 GB heap 001 assumed, but it is the number in this plan most likely to be wrong, and the seeder must be extended to produce a realistic dead-override population so it is measured rather than assumed. See "Risks".

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after design.*

No constitution is defined for this project, so this gate is not evaluated and blocks nothing — the same finding 001 recorded. The design was instead checked against 002's own 33 acceptance criteria; the coverage table below is that check. Three things came out of it worth recording rather than leaving implicit, and they appear as recorded interpretations. One requires a deviation from 001's own migration rules.

### Accepted deviations

| 001 rule | Deviation | Why | Consequence |
|---|---|---|---|
| `data-model.md`, migration strategy: "`audit_event` may only ever gain columns, because its triggers make rewriting rows impossible by design and its content is a legal record" | `V2__override_windows.sql` widens two CHECK constraints on `audit_event` — `source` gains `CLOCK`, `action` gains `BEGIN` and `END` — using the twelve-step rebuild | c30 requires a beginning and an ending to be recorded *as made by the passage of time* and to be **as legible as any other entry**. Every alternative that avoids the rebuild records a lapse as something it is not: `action='REMOVE'` with `actor_id='clock'` reads as *the clock removed this override*, which is exactly the confusion the criterion forbids, and leaves "why did this customer lose access" answerable only by knowing that a particular actor id is a fiction. | The rebuild copies a legal record. The migration must therefore capture `COUNT(*)` and a `SUM`-based checksum over `seq` and `occurred_at` before and after, abort on any mismatch, and log both. **The undo is a Litestream restore to a timestamp before the migration, not the local pre-migration file copy 001 assumed** — on Cloud Run the local filesystem is ephemeral and a copy beside the database dies with the instance. The deploy runbook must therefore record the restore point before the revision carrying V2 goes out. Done once; the rule stands for every later migration. |

### Recorded interpretations

*Readings a reasonable person could take differently. Stated so they are decisions rather than assumptions.*

| Spec text | Interpretation | Basis |
|---|---|---|
| c19, "names every override … that existed at the moment asked about", including removed ones | The explanation names every override **created at or before** the moment asked about, whatever its standing then. For a current answer this includes every override ever created on that account and capability, including removed ones. It does **not** include overrides created afterwards (c25). | The criterion says "existed", and a removed override existed. The cost is that the management snapshot must retain dead overrides in memory — see "Scale/Scope" and "Risks". If the measured footprint makes this untenable, the change to propose is a time bound on how far back the explanation reaches, not silent omission. |
| c13, "a beginning and an ending each reach every product within 60 seconds of taking effect" | Boundaries take effect at midnight of the configured service clock, so this is a promise about the **midnight roll completing and publishing within 60 s**, not about per-override timers. A start date begins at `00:00:00` of that date; an expiry ends at `00:00:00` of the day *after* the expiry date, because the expiry day is inclusive (c4). | §3.1's whole-day semantics make every boundary a midnight. Nothing else in the spec implies finer granularity, and offering finer granularity would contradict the decision to use dates rather than moments. |
| c27, "a date of today returns the current answer" | Asking *as at* today short-circuits to the live snapshot rather than reconstructing from history. | *As it stood at the end of that day* has no meaning before the day is over (§6.1). Reconstructing today from history would also disagree with the live snapshot for any change made in the last few milliseconds, which would look like a bug and be one. |

## Design

### 1. Where a window is evaluated

**In snapshot assembly, in the management service, and nowhere else.**

`SnapshotAssembler` already selects live overrides. It gains one predicate: an override participates if `removed_at IS NULL` **and** its window covers the assembly instant. Everything downstream is unchanged, because from the snapshot's point of view a pending or ended override is simply absent.

This decides four things at once:

- `Resolver.resolve()` is untouched, so the hot path keeps its allocation-free character and `resolverContract` stays at **1**.
- `contracts/snapshot-feed.md` is unchanged. A beginning publishes `override.created`; an ending publishes `override.removed`. Both delta kinds already exist and already mean exactly this.
- `entitlement-client` needs **no change and no release**. The coordinated rollout `future-spec` item 1 predicted does not happen.
- c14 is satisfied structurally: a cut-off replica cannot lapse an override, because it has no window to evaluate and no clock that matters.

One asymmetry to implement carefully: an override created with a future start is **not** published on creation. It enters the feed at its start boundary. A pending override removed before it begins therefore produces no feed event at all, having never been sent. `SnapshotPublisher` must derive delta records from *the difference between consecutive published snapshots*, not from the write that triggered them, or these cases produce phantom events.

### 2. The midnight roll

A single `@Scheduled` job, `WindowBoundaryRoller`, in `entitlement-service`.

- Runs shortly after midnight of the configured zone, and again on a short safety interval so a missed fire is caught within the 60 s budget rather than at the next midnight. Both depend on CPU being allocated between requests — see "Target Platform".
- Recomputes the in-force set, and if it differs from the published snapshot, assembles and publishes a new version in one transaction alongside one `audit_event` per transition (`action='BEGIN'|'END'`, `source='CLOCK'`, `actor_kind='SYSTEM'`, `actor_id='clock'`).
- **Catches up on startup.** If the service was down across one or more midnights, the first run after boot applies every boundary that has since passed. It publishes one snapshot version, not one per missed day — the intermediate states were never observable, and manufacturing versions for them would put fictional moments into the feed.
- Is idempotent. Running it twice in a minute must produce no second version and no duplicate audit rows, because the safety interval guarantees it will happen.

Because boundaries are midnights, the job's work is bounded by the number of overrides whose start or expiry equals the rolling date — a small, indexed query, not a scan.

### 3. What `entitlement-core` gains

Small and additive. `resolve()` is not in this list.

| Type | Change |
|---|---|
| `model/AccountOverride` | Two components: `Optional<LocalDate> startsOn`, `Optional<LocalDate> expiresOn`. Absent on the replica projection, exactly as `reason`, `createdBy` and `createdAt` already are. |
| `model/OverrideStanding` *(new enum)* | `IN_FORCE`, `PENDING`, `ENDED`, `REMOVED`, plus one pure static `OverrideStanding of(AccountOverride override, Optional<LocalDate> removedOn, LocalDate asOf)` — so assembly, the API and the UI share one rule rather than three implementations of it. |
| `model/StandingOverride` *(new record)* | `StandingOverride(AccountOverride override, OverrideStanding standing, Optional<LocalDate> notInForceSince)`. The pairing exists because `AccountOverride` stays a statement of what was agreed, while standing is a statement about a moment; folding standing into the override itself would make the record's meaning depend on when it was read. `notInForceSince` is the expiry-day-plus-one for `ENDED` and the removal date for `REMOVED`; it is empty for `IN_FORCE` and for `PENDING`, whose date is already `startsOn`. |
| `view/EntitlementView` | One new method, `List<StandingOverride> knownOverrides(String accountExternalId, CapabilityKey key)`, with a default implementation that wraps the view's existing live-override reader, marking each `IN_FORCE` with an empty `notInForceSince` — so replicas and every existing test fixture compile and behave exactly as before. |
| `engine/Outcome` | Three values: `NOT_IN_FORCE_PENDING`, `NOT_IN_FORCE_ENDED`, `NOT_IN_FORCE_REMOVED`. |
| `engine/TraceEntry` | Three components: `Optional<LocalDate> startsOn`, `Optional<LocalDate> expiresOn`, `Optional<LocalDate> notInForceSince` — carried straight from the `StandingOverride`, so the trace never recomputes standing. |
| `engine/Resolver.explain()` | Reads `knownOverrides`, folds only the `IN_FORCE` ones into the arithmetic exactly as today, and emits the rest as trace entries carrying a `NOT_IN_FORCE_*` outcome. Winner selection and every existing outcome are computed from the in-force subset alone, so no existing trace changes shape for an account with no windows. |

The property that `resolve()` and `explain()` agree — 001 made it a checked test obligation — now carries the extra clause that adding not-in-force entries never moves the value.

### 4. Answering about the past

`AsAtViewAssembler` in `entitlement-service`, building an `EntitlementView` for a past moment and handing it to the unchanged `Resolver.explain()`.

**A date resolves to an audit sequence first.** `asAtSeq = MAX(seq) WHERE occurred_at < startOfDay(date + 1)`. Every subsequent lookup is "the latest entry at or before `asAtSeq`". This gives a past answer the same *one coherent moment* property `snapshotVersion` gives a live one (v1 c31), and it means a change and its audit row can never be read half-applied.

Four lookups then build the view:

| Fact | Source |
|---|---|
| Which plan the account was on | `audit_event` where `entity_type IN ('ACCOUNT','ACCOUNT_PLAN')` and `account_id = ?`, latest ≤ `asAtSeq`, taking `after_json`. No entry at all ⇒ the account did not exist (c26). |
| What that plan set for the capability | `audit_event` where `entity_type='PLAN_ENTITLEMENT'`, `plan_id`, `capability_id`, latest ≤ `asAtSeq`. Absent ⇒ the plan was silent, so the capability default applies — which is the same distinction v1 c22 already draws. |
| The capability's default and off-value | `audit_event` where `entity_type='CAPABILITY'`, `capability_id`, latest ≤ `asAtSeq`. |
| Which overrides were in force | `account_override` directly: `created_at ≤ boundary`, `(removed_at IS NULL OR removed_at > boundary)`, and the window covering the date. No history needed — this is the half the windows make free. |

`knownOverrides` for a past date returns the same set widened to everything created at or before the boundary, standing computed against that date, so c25 falls out of the same query.

**Refusals, never guesses (c26, c27).** A date before the account's first audit entry, a date beyond the history, and a future date each return their own problem type and never a value. The one that needs care is *beyond the history*: it must be distinguishable from *the account existed and nothing was set*, which is why the assembler checks for the presence of an establishing entry rather than inferring from an empty result.

**Retired capabilities (c28).** The assembler builds the capability as it stood, so a capability retired since the date resolves normally; the response carries `capabilityRetiredSince`. This is deliberately different from asking about a retired capability *today*, which stays the v1 `entitlement/retired-capability` error.

### 5. Retention is already satisfied — by 001's triggers

c32 (seven years) and c33 (establishing entries kept as long as their value stands) need **no pruning logic, because 001 has none and its schema forbids adding any**: `trg_audit_no_delete` raises on `DELETE` against `audit_event`. Retention is therefore unbounded and enforced by the engine rather than by a policy someone must remember.

002's work here is consequently small and mostly evidential: raise the documented floor from twenty-four months to seven years, and add a test that asserts a `DELETE` against `audit_event` is refused — turning a schema property into a demonstrated criterion. The plan records one obligation for the future rather than building it now: **any pruning job ever added must exclude the most recent establishing entry per `(entity_type, entity_id, capability_id)`**, and the test above is what will fail if someone forgets.

### 6. Storage

`V2__override_windows.sql`:

```sql
ALTER TABLE account_override ADD COLUMN starts_on  TEXT;   -- 'YYYY-MM-DD', NULL = from creation
ALTER TABLE account_override ADD COLUMN expires_on TEXT;   -- 'YYYY-MM-DD', NULL = open-ended
```

A `CHECK (starts_on IS NULL OR expires_on IS NULL OR starts_on <= expires_on)` cannot be added to an existing table by `ALTER`, so the ordering rule is enforced in the repository and covered by a test; the no-back-dating rules (c7) are enforced there too, since they compare against the clock and are not expressible as a column constraint at all.

Indexes:

```sql
CREATE INDEX ix_override_window_start ON account_override(starts_on)  WHERE starts_on  IS NOT NULL;
CREATE INDEX ix_override_window_end   ON account_override(expires_on) WHERE expires_on IS NOT NULL;
CREATE INDEX ix_audit_capability      ON audit_event(capability_id, seq DESC);
```

The third is the gap 002 found in v1: `data-model.md` indexes `audit_event` by account, plan, actor and time — the three filters §8 required — but not by capability, which is exactly what two of the four point-in-time lookups need, and what c31's history filter exposes to operators.

The `audit_event` rebuild widens `source` to include `CLOCK` and `action` to include `BEGIN` and `END`, and adds nullable `window_transition TEXT`. See "Accepted deviations" for the verification this migration must carry.

### 7. Contracts

All changes are additive, so `/v1` keeps its additive-only promise and no version is forked.

| Contract | Change |
|---|---|
| `decision-api.md` | `trace.grants[]` and `trace.holds[]` may carry entries whose `outcome` is `NOT_IN_FORCE_*`, with `startsOn`, `expiresOn`, `notInForceSince`. `grantStep.why` gains `NO_GRANTS_IN_FORCE`. **No `asAt` parameter** — the past is an operator surface only (§6.2). |
| `snapshot-feed.md` | **Unchanged.** `format` stays 1, `resolverContract` stays 1. The note in `README.md` predicting a contract bump for this feature is superseded and should be amended in place. |
| `java-client-sdk.md` | **Unchanged.** No SDK release is required by this feature. |
| `admin-api.md` | `POST …/overrides` accepts `startsOn`, `expiresOn`; new `entitlement/invalid-window` (422) covering start-after-expiry, wholly-past windows and back-dated starts. `GET …/accounts/{external}` overrides gain `startsOn`, `expiresOn`, `standing`; `effectNow` stays scoped to in-force overrides. `GET /admin/v1/check` accepts `asAt=YYYY-MM-DD`. `GET /admin/v1/audit` accepts `capability=`. New problem types `entitlement/before-account-existed`, `entitlement/beyond-history`, `entitlement/future-date`. All four are four new constants on 001's existing `error/ErrorCode` enum, which already carries the slug/status/title triple this needs. |
| `ui-screens.md` | Screens 3, 4 and 5 change; screens 1 and 2 do not. |

### 8. UI

**Screen 3, account view** — the override form gains two optional date fields and, beneath them, the sentence the spec requires: *"In force from 1 October to 31 December inclusive."* Blank stays the fastest path through the form. The override list groups by standing, in force first and prominent, with pending, ended and removed present but visibly not counting. The removed group is collapsed by default, since it grows without bound and is the least often wanted.

**Screen 4, checker** — an optional date. When set, a persistent banner states the screen is showing the past and names the date; the current-answer affordance stays one click away. `<TraceView>` renders not-in-force entries dimmed with their reason — *ended 30 June*, *begins 1 October*, *removed 12 May* — and keeps its rule from 001 unchanged: it maps enum values to labels and derives nothing.

**Screen 5, change history** — a capability filter, and rows for beginnings and endings that read as such rather than as an operator's act.

## Coverage

Every criterion in [`spec.md`](./spec.md) §11 maps to a phase below. Nothing is unassigned.

| Criteria | Where |
|---|---|
| 1–4 windows saved, the three not-counting states, inclusive expiry day | Phase 1 (rule), Phase 2 (storage), Phase 4 (over HTTP) |
| 5–6 one named clock, meaning shown in words before saving | Phase 2 (zone validated at boot), **Phase 6** (named and phrased on screen) |
| 7 the three refusals: start after expiry, wholly past, back-dated start | Phase 2 (rules), Phase 4 (`entitlement/invalid-window`) |
| 8–9 no edit; extension by overlap resolves under the unchanged rule | Phase 1 |
| 10–12 only in-force overrides count; a start raises and an end releases with nobody acting | Phase 1 (rule), Phase 3 (the clock reaching the boundary) |
| 13 a beginning and an ending reach every product within 60 s | Phase 3, evidenced in **Phase 7** |
| 14 a cut-off replica goes on honouring an ended override, as intended | Phase 3, evidenced in **Phase 7** |
| 15 every v1 speed and freshness demonstration unchanged | **Phase 7** |
| 16–17 ended overrides never cleared; removal retains the record | Phase 2 |
| 18 account view grouped by standing | Phase 6 |
| 19–21 the explanation names not-in-force overrides, and is still the decision's own | Phase 1 (produced), Phase 4 (carried), Phase 6 (rendered) |
| 22–24 past answers reflect plan, plan value, default and overrides as they stood | Phase 5 |
| 25 overrides created after the date do not appear; those not in force then are marked | Phase 5 |
| 26–28 before-account, beyond-history, future date, today, retired-since | Phase 5 |
| 29 95 of every 100 past answers within 3 s | Phase 5 (built), **Phase 7** (measured) |
| 30 beginnings and endings in history, made by the passage of time | Phase 2 (schema), Phase 3 (written), Phase 6 (read) |
| 31 history filterable by capability | Phase 2 (index), Phase 4 (route), Phase 6 (screen) |
| 32–33 seven-year retention; establishing entries outlive it | Phase 2 |

## Phases

Each phase ends with something demonstrable and independently reviewable. Phases 1–3 and 5 run against the baseline as it stands; **4, 6 and 7 are gated on 001 work that has not landed**, and each names its gate rather than assuming it away. Phase 5 deliberately comes before Phase 4 so that point-in-time is built and property-tested against the assembler while the admin layer is still being written — it needs no controller of its own until the checker route exists.

1. **`entitlement-core`: windows in the model, standing in the explanation.** `AccountOverride` gains its two dates; `OverrideStanding`; `Outcome` and `TraceEntry` additions; `explain()` emits not-in-force entries. `resolve()` untouched, and a test asserts its bytecode-visible signature and behaviour are unchanged. Order-independence properties extended over generated windows. *Deliverable: the engine explains a lapsed grant, with no service and no database.*
2. **Storage and validation.** `V2__override_windows.sql` including the verified `audit_event` rebuild; repository read/write of windows; the three validation rules of c7; the append-only-delete test that evidences c32/c33. *Deliverable: a window round-trips, an invalid one is refused, and the audit table provably refuses deletion.*
3. **Snapshot assembly and the midnight roll.** In-force filtering at assembly; `WindowBoundaryRoller` with zoned next-midnight arithmetic, startup catch-up and idempotency; the two daylight-saving-day tests; delta derivation by snapshot difference rather than by write. *Deliverable: an override lapses at midnight against a driven clock — including on a 23-hour and a 25-hour day — and a replica sees `override.removed` within the budget.*
4. **Admin API.** *Gated on 001 building the override, account, checker and audit admin controllers — only `CapabilityAdminController` exists.* Window fields on create, `standing` on the account view, `capability=` on the audit filter, `asAt` on the checker route, four new `ErrorCode` constants. The cheapest sequencing is for 001 to write those controllers with 002's fields already in mind; the fields are additive, so building them twice is avoidable but not fatal. *Deliverable: every window behaviour is reachable over HTTP.*
5. **Point-in-time.** `AsAtViewAssembler`, `asAtSeq` resolution, the four lookups, the refusal cases, `capabilityRetiredSince`. Buildable and fully testable at the service layer now; only its exposure on the checker route waits for Phase 4. *Deliverable: a past date returns the answer that was live then, evidenced by the history-replay property test.*
6. **UI.** *Gated on 001 building the operator SPA — only the Vite scaffold exists.* Screens 3, 4 and 5. *Deliverable: an operator can set a window, see why access dropped, and ask about last month.*
7. **Demonstration.** *Gated on 001's seeder and load-test harness, neither of which exists.* Re-run 001's k6 harness unchanged for c15; add a boundary scenario for c13, an SDK-cutoff scenario for c14, and a point-in-time timing scenario for c29.

   The seeder needs more from 002 than a dead-override population. `DECISIONS.md` §9 requires the demo to seed **change history as well as data**, with a trail that ends at the state the demo opens in — and for 002 that is not decoration but the precondition for demonstrating anything at all: c22–c29 are unanswerable against a database whose audit trail begins at deployment. The seeder must therefore lay down a coherent backdated history — plan edits, plan reassignments, capability default changes, and overrides whose windows have begun, ended and been removed — so that asking *as at* three months ago returns something both correct and interesting.

   One thing to state plainly so nobody reads it as a defect: **the seeder writes backdated rows directly to the database, which the API refuses to do (c7).** That is not a contradiction. c7 governs what an operator may assert through the service; the seeder is manufacturing a fictional past for a demonstration, and it is the only component permitted to do so. It must be excluded from the no-back-dating tests explicitly rather than by accident.

   *Deliverable: c10–c15 and c29 evidenced rather than asserted, against seeded history deep enough to ask real questions of.*

## Risks

| Risk | Assessment |
|---|---|
| **The management snapshot retains dead overrides for ever** (c19's interpretation) | The one number here most likely to be wrong. Phase 7 measures it against a seeded dead population. If it is untenable, propose a stated time bound on how far back the explanation reaches — never silent omission, which would make c20 false. |
| **The `audit_event` rebuild touches a legal record** | Mitigated by count-and-checksum verification inside the migration, and undone by a Litestream restore rather than a local file copy. Done once. |
| **Phase 4 lands on controllers 001 has not written** | The additive fields are cheap to add twice, so this costs rework rather than correctness. Worth one conversation with whoever writes 001's override and checker controllers before they start, not a blocking dependency. |
| **The demo's seeded history is the whole evidence base for the point-in-time half** | If the seeder lays down data without a matching audit trail, c22–c29 become undemonstrable and it will not be obvious why — the feature will look broken rather than unseeded. Phase 7 treats the trail as the deliverable, not a by-product. |
| **A stray `Instant.now()` makes a boundary test flaky** | Mitigated by the injected `Clock` and an enforced build-time ban. Worth the enforcement precisely because the failure mode is intermittent rather than loud. |
| **Someone adds a window case to the conformance vectors** | Would make every replica refuse to serve, since their engines deliberately do not evaluate windows. Needs a comment in the vector fixture stating why, and a test asserting no vector carries a window. |
| **The two daylight-saving days** | The failure is silent and twice-yearly, which is the worst combination for finding it late. Mitigated by computing the next boundary as zoned arithmetic rather than a fixed duration, and by fixed-date tests that drive the clock across the March and November transitions and assert an override in force on the transition day is still in force the whole of it. |
| **Someone outside Eastern is surprised by the boundary** | Accepted and recorded in the spec's limitations. The mitigation is naming the clock wherever a date is shown, which Phase 6 must not skip as decoration. |

## Complexity Tracking

No constitution is defined, so there are no violations to justify. The one departure from 001's rules — the `audit_event` CHECK widening — is recorded under "Accepted deviations", where it will not be mistaken for a waived principle.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| — | — | — |
