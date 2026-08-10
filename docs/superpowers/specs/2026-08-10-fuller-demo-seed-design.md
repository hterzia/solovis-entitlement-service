# A demo dataset that reads like a real deployment

Date: 2026-08-10
Status: approved, not yet implemented; revised after 002 merged into `main`
Baseline: post-002 `main` (`V4__override_windows.sql`, windowed overrides, `AsAt` read path)
Surface: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/`

## Problem

`DemoDataSeeder` writes four capabilities, two plans and two accounts. That was enough to
prove the write paths worked. It is not enough for the Cloud Run deployment reviewers
actually look at, where it produces five screens that all read as scaffolding:

- **Capabilities** — four keys in four areas, so the tree groups nothing and the `area`
  and `q` filters (`CapabilityAdminController.java:30`) have nothing to filter.
- **Accounts** — two rows, so the cursor paging and search in `AccountsListRoute.tsx:16`
  never appear.
- **History** — about seventeen events, all stamped within the same second of container
  boot, which announces the data as generated more loudly than any amount of volume
  would fix.
- **Plans** — `Free` and `Pro`, which no institutional investment platform has.
- **Checker** — one interesting answer to look up.

The dataset also never shows the rule the whole service is built around. 002 added a HOLD
to the seed, but it is removed in the next statement, so it exists to demonstrate the
*removed* standing rather than the combining rule — no seeded account has a HOLD holding a
GRANT down, and no e2e test creates one. *A restriction always defeats a concession*
(spec §4) is therefore never shown on a screen. `unlimited` is absent entirely, as is any
retired capability.

## Decisions taken

| Question | Decision |
|---|---|
| Audience | The hosted Cloud Run demo, shown to people evaluating the work |
| Scale | ~60 accounts, 16 capabilities over 6 areas (one of them retired), 5 plans, deep histories on 6–8 flagship accounts |
| Where the data lives | A versioned JSON resource plus a small applier; the long tail of ordinary accounts from a compact table in the same file |
| Existing fixtures | Every key the e2e suite locates by is preserved; display names and descriptions are re-authored |
| Safety | A seed marker in `service_state` with loud failure, and seeding completed before the port opens |
| Sequencing | 002 merged into `main` while this design was being written (`V4__override_windows.sql`), so the dataset carries override windows from the start |
| History | A seed-scoped mutable clock, wound through an authored eight-month timeline |

## Design

### 1. The dataset is data

`entitlement-service/src/main/resources/seed/demo-seed.json` holds the catalogue and the
timeline. `seed/SeedDataset.java` is the record shape Jackson binds it to; `DemoDataSeeder`
shrinks to a loader that walks it in chronological order and drives the same four admin
services it drives today.

Writing through `CapabilityAdminService` / `PlanAdminService` / `AccountAdminService` /
`OverrideAdminService` is the property worth keeping from the current seeder: the seed
cannot declare data the validation rules reject, it produces real audit events, and every
write publishes a snapshot exactly as an operator write does. Nothing in this design
bypasses them.

Dates in the file are **relative** — `"day": 42` of the timeline, never `2026-01-15` — so
the demo is always "the last eight months" whenever the container boots, instead of aging
into a system nobody has touched since last year.

The plan applier performs the real `preview` → `previewToken` → `apply` handshake for each
plan generically, which also removes the duplicated entitlement-map literal that the
current seeder carries at lines 64–73 purely to satisfy the token.

### 2. The catalogue

Sixteen capabilities across six areas — fifteen active and one retired. The four existing
keys keep their keys, tiers and value types, and gain institutional display names and
descriptions.

| Area | Capabilities |
|---|---|
| `portfolio` | `portfolio.count` (QUANTITY), `portfolio.lookthrough` (SWITCH), `portfolio.private-markets` (SWITCH) |
| `reports` | `reports.monthly` *(kept)*, `reports.custom-templates` (QUANTITY), `reports.scheduled-delivery` (SWITCH) |
| `data` | `data.refresh-frequency` (TIER: monthly ‹ weekly ‹ daily ‹ intraday), `data.custodian-feeds` (QUANTITY), `data.history-years` (QUANTITY) |
| `api` | `api.access` *(kept)*, `api.export-bulk` (SWITCH), `api.rate-limit` (QUANTITY) |
| `support` | `support.tier` *(kept, same three tiers)*, `support.named-analyst` (SWITCH) |
| `seats` | `seats.count` *(kept)* |

One further capability, `reports.legacy-export`, is created early in the timeline and
**retired** partway through, so the retired state and the `entitlement/capability-retired`
error are demonstrable rather than documented.

Five plans. Keys `free` and `pro` are preserved and displayed as **Evaluation** (the
designated default) and **Professional**, joined by **Core**, **Enterprise** and **OCIO
Partner**. Enterprise sets `reports.monthly` and `data.custodian-feeds` to `unlimited`,
putting the third value variant on a screen for the first time.

About sixty accounts — pension funds, endowments, foundations, insurers, family offices
and OCIOs. `acct_9931` remains Northwind Capital on Professional, carrying its GRANT of
200 monthly reports with the reason "Renewal concession — Q3 pilot" exactly as the e2e
suite asserts. `acct_1177` keeps its key and takes an institutional name.

Six to eight flagship accounts carry authored stories, each chosen to put one behaviour of
the resolution rule on screen:

1. **A HOLD defeating a GRANT** — `api.access` granted for a pilot, then held pending a
   security review. The headline rule of §4, currently invisible everywhere outside core's
   property tests.
2. **Competing GRANTs**, so the explanation names the loser as well as the winner.
3. **A removed override**, leaving CREATE and REMOVE in the history with both reasons.
4. **An account that moved plans twice** across the timeline.
5. **An account resolving `unlimited`** from its plan.
6. **An account touching the retired capability.**
7. Northwind Capital's existing GRANT-beats-plan story, unchanged.
8. **All four standings on one account** — a concession that ran and **ended** months ago, one
   in force with an expiry still ahead of it, and one removed early — so screen 3's grouping
   has every group populated and the checker's past-date answers have something to differ
   about.

The remaining ~50 accounts come from a tail block in the same file — name, plan, join day —
which is what pushes the account list past its 50-row page and makes search and paging real
rather than decorative.

### 3. Seed-clock time travel

`AuditRecorder` stamps every event from the injected `Clock` bean
(`audit/AuditRecorder.java:23`), and main has exactly one such bean
(`time/ClockConfig.java:12`). Written the obvious way, roughly 160 audit events would
therefore share a single timestamp.

`seed/SeedClock.java` is a `Clock` with a mutable instant, contributed as *the* `Clock`
bean only when `entitlement.seed.enabled=true`. Unwound it simply delegates, so it is byte
for byte the behaviour of the existing bean. The seeder winds it forward through the
authored timeline, and releases it in a `finally` before returning, after which it
delegates forever.

It **decorates the clock `ClockConfig` builds** rather than constructing its own. Two
reasons, both enforced by tests already in the repository: `NoDirectClockAccessTest` bans
`Clock.systemUTC()` everywhere except `ClockConfig.java`, and since 002 the service clock
carries the service zone (`Clock.system(entitlementZone)`), which is what makes
`LocalDate.now(clock)` mean the operator-facing date rather than the host's. A `SeedClock`
wrapping UTC would compile, pass its own tests, and silently change what every window date
means.

The consequence is that every audit row, every `created_at`, and every plan-assignment
timestamp lands where the story says it did, and the change history shows eight months of
activity with quiet weeks and busy ones. Nothing bypasses validation to achieve it.

**The timeline ends at the real present** — its last events land minutes ago. This is not
cosmetic: the feed reads the current `snapshot_version` row's `publishedAt` on every poll,
so a timeline that stopped in March would present every replica with a months-stale
snapshot.

### 4. Ordering: seeded before the port opens

`DemoDataSeeder` becomes an `InitializingBean` annotated `@DependsOn("snapshotStartup")`,
joining `SnapshotStartup` and `ConformanceAnnouncementStartup`, which are both
`InitializingBean`s for the reason stated in their javadoc: Boot starts the web connector
during context refresh, before any `ApplicationRunner` fires.

The dependency is required, not stylistic — `SnapshotPublisher` mutates from the current
snapshot, so the snapshot must exist before the first admin write.

Two things follow. The console is never reachable in a half-populated state, and
Playwright's wait on `/actuator/health` becomes a real guarantee that the fixtures exist
rather than a race the Vite startup happens to cover.

A third thing follows that 002 made load-bearing. `WindowBoundaryRoller` is
`@Scheduled(fixedDelayString = "…", initialDelay = 0)`, so it runs the moment the
scheduler starts — and it reads `LocalDate.now(clock)`. Scheduled tasks start with the
context lifecycle, *after* every `InitializingBean` has finished, so the roller cannot
observe a wound clock. That is not a happy accident to rely on silently: were the seeder
ever moved back to an `ApplicationRunner`, the roller would fire mid-seed, take an authored
day for today, and record `window.rolled-through` somewhere in the middle of the fictional
past. On release it would then catch up day by day and publish a flood of boundary
transitions for moments nobody observed — precisely what the roller's own first-run comment
exists to prevent. **The seeder being an `InitializingBean` is what keeps the wound clock
away from the scheduler thread.**

### 5. A marker, and loud failure

The current guard infers "already seeded" from whether any plan exists — a single check
over a twelve-step non-atomic sequence, which at ~215 steps becomes a real hazard: a crash
after the first plan leaves a permanently half-populated demo that every later boot
silently skips.

The seeder instead writes to `service_state` (V3), which exists for facts like this and is
explicitly never pruned:

- `seed.started` — dataset version and file digest, written before the first write.
- `seed.completed` — written after the last.

On boot: `completed` → skip, logging the version found. Neither → seed, logging each phase
and a summary line. `started` without `completed` → log the phase it died in and **fail
startup**. A container that will not start is a better failure than a demo that is quietly
missing half its data, and for a throwaway demo database the recovery is to delete the file.

### 6. Cost

Roughly 160 admin calls — 17 capability writes, 11 plan writes, 60 account creations, about
45 plan reassignments and about 25 override operations — each its own transaction and
snapshot publish. On the order of one to three seconds on Cloud Run's single CPU, paid once
per seeding boot, now before the port opens.

The ~160 `snapshot_version` delta rows this creates are dated in the authored past, so the
existing hourly `SnapshotVersionPruner` sweep clears them within an hour of boot, leaving
the version being served. That is the retention rule behaving exactly as documented, not a
special case.

## Windows, and the fourth standing

002 merged into `main` while this design was being written, so override windows are not
future work: `startsOn` / `expiresOn` are fields on `OverrideCreateRequest`, and the seeder
already writes three of the four standings onto `acct_1177`.

Its own comment records why the fourth is missing:

> ENDED is missing from this list on purpose. c7 forbids saving a wholly-past window
> through the API, and the seeder writes through the same admin services as everything else,
> so it cannot manufacture one either.

**The seed clock dissolves that constraint without weakening the principle it protects.**
`OverrideAdminService` validates a window with `WindowRules.validate(startsOn, expiresOn,
LocalDate.now(clock))`. Wound to authored day 90, a window running day 90 → day 140 is an
ordinary, fully validated admin write; by the time the demo is served it has ended. The
seeder still goes through the admin services — it simply is not standing in the present when
it does. All four standings become seedable, and the permission `WindowRules`' javadoc
reserves for the seeder to bypass the repository stays unused, which is the outcome that
paragraph would prefer.

Three constraints on the dataset follow from what is already there:

- **`acct_9931` gains nothing.** The e2e suite asserts on its resolved state; a second
  override on its capabilities changes the answer and fails tests that are correct.
- **`acct_1177`'s three overrides are carried into the JSON verbatim** — `seats.count` in
  force and expiring today, `reports.monthly` pending, `api.access` held then removed. They
  are what screen 3's grouping renders, and `windows.spec.ts` uses that account as its
  scratch space. It gets no authored plan moves on top; the richer window stories go on new
  flagship accounts instead.
- **The last authored write still lands at the present.** This now has a second reason
  beyond feed freshness: the replica projection evaluates standings before publication, so
  the final published snapshot must be evaluated at a moment that is effectively now.

## Testing

- **`SeedDatasetTest`** (no Spring) — the file parses, every capability, plan and account
  referenced by an event exists, days are non-decreasing, keys are unique, and every window
  is coherent — start on or before expiry, and neither already past on the authored day it
  is written, which is the rule `WindowRules` will apply against the wound clock.
- **`DemoDataSeederIT`** — its own context with `entitlement.seed.enabled=true` and a
  throwaway database, running the whole seed. Asserts the resulting counts, that
  `acct_9931` still resolves 200 sourced from `GRANT`, that a HOLD defeats a GRANT on the
  flagship that carries one, that audit timestamps span months and the newest is within a
  minute of now, that the windows flagship shows all four standings — including the ENDED
  one that could not be seeded before — and that the `Clock` bean reads real time once
  seeding has finished. This
  is the first test of the seeder's happy path, which today is exercised only by e2e and by
  the deployment itself.
- **e2e** — one assertion changes: the default-plan test matches on the display text
  `Free`, which becomes `Evaluation`. Every other locator in `operator-screens.spec.ts` is a
  preserved key, and `windows.spec.ts` is untouched because `acct_1177` keeps the three
  overrides it asserts around and creates its own capability for everything else.

## Out of scope

- The MSW component fixtures (`src/test/mocks/fixtures.ts`), which are an independent
  hand-maintained set and already use keys the service does not have (`support`, `seats`).
  Worth reconciling; not part of this change.
- Any load-test or volume seed. Criteria 25–27 were withdrawn and the supported client base
  is 300 (spec §7); this dataset is authored for legibility, not throughput.
- Turning seeding off in production. It stays on, and the marker plus the log lines are what
  make its behaviour legible.
