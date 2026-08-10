# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A spec-first build of the **Solovis Entitlement Service**: one service that answers, for any account and any capability, *are they allowed*, *what is the value*, and *how was that decided*.

The written specification is the source of truth and is considerably ahead of the code. Before changing behaviour, read the spec section that governs it — most "obvious improvements" are already recorded as deliberate exclusions.

## Commands

Backend (Maven reactor root is `management/backend`, not the repo root):

```bash
cd management/backend
./mvnw test                              # whole reactor
./mvnw -pl entitlement-core test         # core only (no dependencies, fast)
./mvnw -pl entitlement-service -am test  # -am is REQUIRED: service depends on core
                                         # (without it: "Could not find artifact entitlement-core")
./mvnw -pl entitlement-core test -Dtest=ResolverResolveTest          # single class
./mvnw -pl entitlement-core test -Dtest=ResolverResolveTest#methodName
./mvnw spring-boot:run -pl entitlement-service -am                   # serves on port 8081
```

The app already binds `0.0.0.0:8081` (`application.yaml`) — on a headless host, give the machine's LAN URL, never `localhost`. Swagger UI at `/swagger-ui.html`. DB file defaults to `management/backend/entitlement-service/data/entitlement.db` (`ENTITLEMENT_DB_PATH` overrides).

Frontend (`management/frontend/management-ui` — React 19 + TanStack Router/Query; `node_modules` installed):

```bash
npm run dev      # vite.config.ts already sets host 0.0.0.0 and proxies /admin + /v1 to :8081
npm run build    # tsc -b && vite build
npm run test     # vitest run (jsdom + Testing Library + MSW)
npm run test:e2e # playwright: real SPA against a real service — starts both, see below
npm run lint     # oxlint
```

Run the backend on 8081 first; the dev server proxies to it, so the SPA needs no CORS handling. The proxy target is `ENTITLEMENT_API_URL` (default `http://localhost:8081`).

**End-to-end (`e2e/`)** covers the five §9 screens against a real `entitlement-service`, and is the only test that can catch the SPA and the admin API disagreeing — the failure mode that produced all five bugs in the UI contract-fixes plan, none of which the 116 MSW-backed component tests could see. `npm run test:e2e` starts everything: `e2e/start-backend.sh` installs core+client, launches the service on **8099** with a throwaway SQLite file and `entitlement.seed.enabled=true`, and Vite serves the SPA on **5199**. Tests share that one service and run serially, so a fixture one test creates is visible to the next — prefer exact, scoped locators (a capability key like `e2e.probe.switch` contains the substring "pro"). Chromium is already in `~/.cache/ms-playwright`.

**The suite needs that service to itself.** Several assertions describe the seeded account's *resolved* state — `acct_9931`'s GRANT of 200 is `winning`, and `reports.monthly` therefore sources from `GRANT` — which anything else writing to the same backend will change. Adding one HOLD from another terminal flips both to `overridden by a HOLD` and fails two tests that are perfectly correct. If e2e fails in a way that looks like a regression in resolution, check for a second client on 8099 before believing it; `start-backend.sh` wipes the DB on every launch, so a restart is the reset.

## Architecture

The whole design follows from one requirement: **a decision must be answerable inside a consuming product, from a local replica, while the management service is down** (spec §11). That forces the resolution rule to be the *same code* in both places, which is why the reactor splits three ways:

- **`entitlement-core`** — pure Java 21, no Spring, no I/O. Domain model, the total order over values, the resolver, the trace, the immutable snapshot. Both other modules depend on it; neither reimplements any resolution logic.
- **`entitlement-service`** — Spring Boot 4 app. SQLite system of record, mutation + audit, admin/decision/feed REST APIs, hosts the built SPA as static assets.
- **`entitlement-client`** — SDK products embed. JDK `HttpClient` + Jackson only (no Spring) so any JVM service can use it.

Load-bearing consequences:

- **SQLite is on the decision path.** Reads resolve directly from SQLite, through a dedicated read connection pool, inside a read-only transaction; there is no in-memory `Snapshot` held for the service's own decisions. `SnapshotMutator` still exists (structural sharing over an in-memory `Snapshot`), but is no longer part of the read path — its only service-side use today is building the hypothetical snapshot behind a plan-edit preview; consuming clients' SDK replicas are the ones that legitimately still hold a snapshot.
- **Replicas carry the *model*, not computed answers** — capabilities, plans, account→plan, overrides. Decisions are local and sub-millisecond; deltas stay small.
- **Explanations exist only in the management service.** `Resolver.resolve()` returns `(allowed, value)` and allocates nothing; `Resolver.explain()` runs the *identical* arithmetic and layers a `Trace` on top, so the two cannot disagree. The SDK never traces — override reason text ("suspended pending investigation") must not reach consuming services.
- **Replica drift is guarded structurally**, not by discipline: the feed carries a `format` version, a `resolverContract` version (`core/conformance/ResolverContract.VERSION`), and conformance vectors each replica evaluates at startup, refusing to serve on mismatch. Bump `resolverContract` whenever §4's combining rule changes. The vector set (`ConformanceVector.spec5WorkedExamples()`, 49 vectors) is the *whole* of that defence, so its breadth is itself tested — `ConformanceCheckTest` asserts tier-with-override, declared quantity off-value, competing GRANTs/HOLDs, `unlimited`-as-result and silent-plan cases are all present. Adding a vector without changing the rule is a build change, not a semantics change: it is announced by a **`conformance.changed` delta** at startup (`ConformanceAnnouncer`, digest kept in `service_state`) so a replica that stayed up across the deploy re-gates instead of coasting on the set it started with, and it must *not* bump `resolverContract`.
- **`snapshot_version` is pruned; `audit_event` never is.** `SnapshotVersionPruner` drops delta rows past `entitlement.snapshot.delta-retention` (7d), which is what makes `410 snapshot-too-old` — and therefore the SDK's whole full-resync path — a real behaviour rather than a documented intention. It never prunes the version currently being served, because the feed reads that row's `publishedAt` on every poll. The audit trail is the opposite: §8 forbids removal and triggers enforce it. Never give the two one retention story.
- **SQLite permits one writer host**, so the management service does not horizontally scale. Decision-path availability comes from SDK replicas, not service redundancy.

### The resolution rule (spec §4) — the thing everything protects

Baseline (plan entitlement, else capability default) → raised by the **most generous GRANT** → capped by the **most restrictive HOLD**. A restriction always defeats a concession. Order of overrides never affects the result (property-tested with jqwik). Value types are `SWITCH`, `QUANTITY`, `TIER`; `unlimited` is a **distinct variant, never a large number**, and tiers carry a declared total order.

## Spec-first workflow

```
.specs/                                 # one self-contained folder per feature; no top-level specs/
├── future-spec.md                      # deliberately deferred scope, with triggers and dependencies
├── 001-entitlement-service/            # v1: spec.md (source of truth; criteria numbered to 41,
│   │                                   #   with 25-27 withdrawn and the gap kept on purpose)
│   ├── plan.md research.md data-model.md frontend-plan.md
│   └── contracts/                      # README (shared conventions) + decision-api, admin-api,
│                                       #   snapshot-feed, java-client-sdk, ui-screens
├── 002-time-bound-override/            # override expiry + point-in-time answers
└── 003-natural-language-procesing/     # plain-English checker
DECISIONS.md                            # running log of decisions taken during spec/plan review
docs/superpowers/plans/                 # task-by-task implementation plans (checkbox steps)
```

- **Specs are business documents.** Keep technical vocabulary out of `.specs/**/spec.md`; state the business posture there and leave mechanism to `plan.md` / `research.md` / `contracts/`.
- Criterion references appear throughout code and docs as `(cNN)` / `c30` — they point at `spec.md` §10.
- Implementation plans in `docs/superpowers/plans/` are executed with `superpowers:subagent-driven-development` or `superpowers:executing-plans`, task by task, test-first, one commit per task.
- **All five plans there are finished and merged**, and each carries a "Status: complete" banner saying so. Their checkboxes are all still `- [ ]` — boxes were never ticked back, by convention. Read the banner, not the boxes, and treat the files as archived records of how the code got here rather than as outstanding work.
- The working tree usually carries long-lived unrelated pending changes under `.specs/**` and `DECISIONS.md`, and work is often split across git worktrees under `.claude/worktrees/`. **Never `git add -A`** — stage only the files the current task names.
- `refs/l4j-examples` is a vendored read-only reference checkout (LangChain4j examples). Not part of the build; do not modify or lint it.

## Conventions and traps

- **Package root** `com.solovis.entitlement.{core,service,client}`. Repositories and `*Row` records live in `service/store/`; empty `package-info.java` files mark packages the plans will fill.
- **No JPA/Hibernate.** Hand-written SQL through Spring's `JdbcClient`, explicit `RowMapper` lambdas onto `record` row shapes.
- **Two datasources over one file**: inject `@Qualifier("entitlementWriteJdbcClient")` (pool size 1, Flyway migrates through it) or `entitlementReadJdbcClient`. The `store/*Repository` classes (admin reads and writes alike) still use the write client on purpose — the admin surface has no read-your-writes-safe transaction boundary of its own to reason about. The decision path is the one deliberate exception: `DecisionReadDao` and `RecordViewAssembler` read through `entitlementReadJdbcClient` inside a dedicated read-only transaction, where SQLite's WAL isolation stands in for that boundary (c31).
- **`sql-error-codes.xml` in service resources is load-bearing.** Xerial sqlite-jdbc returns `sqlState=null` on every constraint violation, so without it *all* violations surface as `UncategorizedSQLException` instead of `DataIntegrityViolationException`.
- **Timestamps** are ISO-8601 UTC text with milliseconds, always computed in Java and passed in — never `datetime('now')` inside SQL. Inject `java.time.Clock`; never call `Instant.now()` directly in a controller or service. The clock **carries the service zone** (`entitlement.clock.zone`, US Eastern) so `LocalDate.now(clock)` is the operator-facing date rather than the host's; `ServiceZone` refuses to boot on a zone whose midnight is ambiguous, and `NoDirectClockAccessTest` enforces the ban. Stored instants stay UTC — the zone governs how a *date* is read, never how a moment is written.
- **`seq` order and `occurred_at` order must agree in `audit_event`.** Point-in-time resolves a date to `MAX(seq)` below a boundary and bounds every lookup by it, so a row with an early `occurred_at` and a high `seq` makes a past question return today's answer, silently. Nothing in the schema can enforce it; `AuditTrailOrderingTest` states it. This is why a backdated demo history cannot be appended to an existing database — it has to be written in time order or not at all.
- **`capability.area` is always derived** from the key (substring before the first `.`), never taken from a caller.
- **Immutability rules enforced by schema/triggers**: the audit trail is append-only; capabilities are retired, never deleted; overrides are never edited (remove + recreate, both with reasons); tiers may be appended above the max ordinal but never inserted between existing ones (renumbering would silently rewrite stored values); exactly one default plan.
- **The three §6.3 errors are errors, never denials** — unknown account, unknown capability, retired capability each get their own status and `entitlement/*` slug. "We don't know" and "no" are different answers (c19). Error model is RFC 9457; callers branch on `type`, never on message text.
- **Wire vocabulary is defined once** and reused by every surface (`contracts/README.md`): the three-variant value encoding, the capability descriptor, the error model. Don't redefine a `ValueDto` per package.
- **`X-Entitlement-Snapshot-Version` goes on every `/v1` response**, successes and errors alike (`api/SnapshotVersionHeader`). It is set at each producing site, never by a filter: a filter could only report the latest published version at the moment it ran, which is a different question from "which version answered this request", and a write landing between the two reads would make the header describe a version the body was never resolved against. `/admin/v1` is out of scope — except `/admin/v1/check`, which returns the `/v1` payload byte for byte and so copies its headers.
- **Migrations run V1, V3, V4, V5 — the V2 gap is permanent.** 002's window migration was numbered V2 on a branch cut before V3 existed, and was renumbered to V4 when it merged: Flyway defaults to `out-of-order=false` with `validate-on-migrate=true`, and the deployed database is restored from GCS rather than rebuilt, so a V2 arriving after V3 is a hard startup failure. **Never edit an applied migration to tidy the gap** — checksums are recorded, and changing a comment fails validation the same way. **V5 is a Java migration** (`service/migration/V5__audit_window_transitions`) reaching Flyway as a `@Component`, not by location scanning: it rebuilds `audit_event` to widen a CHECK, which needs `PRAGMA foreign_keys=OFF`, which SQLite ignores inside a transaction — so it returns `canExecuteInTransaction() == false` and manages its own.
- **Every mutating admin path is one `@Transactional` method with the same four beats**: validate against the registry → write the row (removals are soft) → append the audit event → `SnapshotPublisher.publish()` **last**. `publish` must be called inside the transaction and throws if it is not; the version is the `snapshot_version` row's own autoincrement key, so the number lives in exactly one place and two publishes in one transaction are fine. **Two deliberate exceptions, both 002 phantom-event guards**: creating an override whose window has not begun publishes nothing, and removing one that was never in force publishes nothing — there is no model change for a replica to learn about, and manufacturing a version would put a fictional moment in the feed.
- **`WindowBoundaryRoller` is publication, not correctness.** The service evaluates windows as a SQL predicate at read time, so its own answers are right whether or not the roll runs; the roll exists to tell replicas and to write the `BEGIN`/`END` audit rows. It uses a zoned cron (a day is not always 86,400 seconds) plus a safety interval, keeps `window.rolledThrough` in `service_state` so idempotence and startup catch-up are one mechanism, and opens its transaction explicitly because `roll()` calls `rollOneBoundary()` on `this` — a self-invocation never passes through the `@Transactional` proxy. Disabled in tests via `entitlement.window.roll-enabled: false`.
- `DemoDataSeeder` is an `ApplicationRunner` (`@Order(1)`, gated on `entitlement.seed.enabled`) and writes through the real admin services, so it can never declare data the validation rules reject.
- **Tests**: JUnit 5 + AssertJ; jqwik for order-independence properties; Spring tests run against a temp-file SQLite DB (`src/test/resources/application.yaml`, one file per JVM run) — no Testcontainers, no H2. Frontend: Vitest + Testing Library + MSW (`src/test/mocks/`).

## Deliberate v1 absences — do not "fix" these

- **No authentication and no roles** (user decision). Every endpoint is open; an `ActorResolver` seam supplies a stub identity so audit records stay complete. Acceptance criterion 37 is not demonstrable. The service must not leave a trusted network until sign-in lands.
- No plan rollback, no plan versioning, no `parentPlanKey`, no override edit route, no capability delete, no usage tracking (this service owns the *limit*, not the counter).
- Ungated capability default / off-value edits (accepted limitation, spec §12).
- Every deferral is documented with its trigger in `.specs/future-spec.md` — check there before proposing one.

## UI

`.claude/design/solovis/tokens.css` is the authoritative design source (extracted from Solovis production, not reconstructed). No colour, radius, spacing or type value may be introduced that isn't one of its custom properties or `.sv-*` classes. Use the `solovis-designer` agent for any Solovis-facing visual output. The SPA builds to Vite `dist` and is copied into the service's `static/` — one deployable, no CORS story. That copy **is** wired into the build: `frontend-maven-plugin` (node + `npm ci` + `npm run build`) and `maven-resources-plugin` both bind to **`prepare-package`** in `entitlement-service/pom.xml`, so `./mvnw test` stays fast and offline while `package`/`install` produce a jar that actually serves the UI. Consequences worth knowing: bare `./mvnw spring-boot:run` does *not* traverse that phase and serves an empty `static/` — use `./mvnw package && java -jar target/*.jar`, or just run the Vite dev server for frontend work.

**The Cloud Run container does not use that Maven plugin at all.** `Dockerfile` builds the SPA in a node stage (`npm ci && npm run build`), copies `dist/` into `entitlement-service/src/main/resources/static/`, then runs `./mvnw -pl entitlement-service -am package -DskipTests`. So the deployed artifact depends on `npm run build` (`tsc -b && vite build`) succeeding — which `npm run test` and `tsc -b` alone do not prove. Verified end to end on 2026-08-10: the SPA ships inside the jar under `BOOT-INF/classes/static/`, **every SPA route deep-links and survives a reload** (a forwarding controller returns the shell for `/accounts/{id}` and friends), and the fallback does *not* swallow API 404s — `/admin/v1/no-such-route` still answers with an RFC 9457 problem. Re-check those two properties together whenever routing or the static-resource configuration changes; the Vite dev server has history fallback built in, so **no dev-server test can catch a regression here**.

Nine routes across the five §9 screens: `/capabilities` + `/capabilities/$key`, `/plans` + `/plans/$key`, `/accounts` + `/accounts/$external`, `/checker`, `/history`, and `/`. Every route declares `staticData.requiredRole`; **nothing reads it yet** — it is the seam for the deferred role enforcement, so turning it on is a guard, not a rewrite.

Every mutation confirmation reads **"Saved. Active everywhere within {N} seconds."** with `{N}` from `GET /admin/v1/meta`, never a hard-coded 60 (c41).

**The SPA is dumb, and this is structural.** It renders what the service returns and maps enum values to labels (`CAPABILITY_DEFAULT` → `default`, `WINNING` → `winning`). It must never re-derive an entitlement value — that would put a second implementation of §4's combining rule in the least defensible place. When a screen needs an answer only the resolver can give, add a route. That is why `GET /admin/v1/accounts/{external}/overrides/{id}/removal-preview` exists: the remove-override confirmation has to state what the value returns to *before* the operator commits (c14/c15), and the honest way to get that number is to ask the service. Read-only — no soft-delete, no audit event, no publish.

**Every mutation and every detail read renders its failure**, via `<ErrorNotice error={…} action="…" />`, which prints the RFC 9457 `detail` (else `title`) in a `role="alert"` and never re-words it. A detail route whose entity 404s shows that error, never an indefinite "Loading…". This is worth guarding: the app previously had 18 mutations and surfaced an error for one, so a rejected write was indistinguishable from a successful one.

**A successful write must never render nothing.** Five mutations take the c41 number from `GET /admin/v1/meta`, a query unrelated to the write; gating the confirmation on `meta.data` meant a completed save showed *nothing at all* when that one query failed. `SaveConfirmation`'s `seconds` is optional for exactly this reason — it always says "Saved." and adds the promise only when the number is known. Only the override mutations and the plan apply carry the number in their own response; the rest genuinely depend on `meta`.

**Never `reset()` a mutation that is still pending, and never render its notice inside a collapsible.** Both discard the outcome of a write that has already been sent. Editing a field calls `reset()` so a stale "Saved." cannot sit above a changed form — but on a *pending* mutation that detaches the observer, and neither the failure nor the success ever renders while the write lands anyway. One keystroke during a slow save was enough. Guard every such call with `if (!mutation.isPending)`, keep each `<ErrorNotice>` outside the panel it reports on, and where a control would unmount the notice (the capability create form's Cancel), disable it for the round trip. Component tests catch this only if they type or dismiss *during* the request — see the mid-flight tests in `CapabilityDetailRoute.test.tsx` and `AccountDetailRoute.test.tsx`.

**`navigator.clipboard` is unavailable in this deployment** — it needs a secure context and the console is served over plain HTTP on a LAN host. Use `lib/clipboard.ts`'s `copyText()`, which falls back to a selection copy and reports whether it worked; calling the async API directly throws on every origin an operator actually uses.

**Component tests are MSW-backed and cannot catch SPA/service disagreement** — that is what `e2e/` is for, and it is where the removal-preview, the non-secure-origin clipboard path, and the error surfacing are actually proved. A behaviour that only exists in a handler has not been demonstrated.

## Current state

Built and tested as of 2026-08-10, all green: **625 backend** (core 124, client 168, service 333), **209 frontend unit**, **33 end-to-end**. Feature 002 (time-bounded overrides and point-in-time answers) is merged; its plan is `.specs/002-time-bound-override/plan.md`. Count backend tests from a clean run — `target/surefire-reports/` keeps stale files from single-class runs and will over-report otherwise.

- **`entitlement-core`** — model, order, resolver + explain, snapshot, mutator, conformance.
- **`entitlement-service`** — all packages: `store/` (dual-pool SQLite, `V1__baseline.sql`, one repository per table), `snapshot/`, `api/` (decision + feed), `admin/` (eight controllers over `admin/service/`), `audit/`, `error/`, `seed/`, `config/`.
- **`entitlement-client`** — replica (NDJSON reader, delta applier, conformance gate, disk cache), transport (JDK `HttpClient` + gzip + RFC 9457), the sync loop, the decision path, service-fetched `explain`, the unknown-account read-through, read-your-writes, and an optional Micrometer seam. `entitlement-service` carries a **test-scoped** dependency on it for `ClientAgainstRealFeedTest`, which points a real client at the real running service — the only test that would catch the service's wire format drifting away from the SDK's parser.
- **`management-ui`** — all nine routes plus `TraceView`, `ValueEditor`, `CapabilityTree`, `SaveConfirmation`, `ErrorNotice`, `lib/clipboard` and the typed API layer.

Not built:

- **No load-test module, and no throughput target.** Criteria 25–27 were withdrawn on 2026-08-10 when the supported client base was settled at 300 (spec §7; `future-spec.md` item 13). **The criterion numbering keeps its gap on purpose** — `(cNN)` identifiers are cited throughout the code, so renumbering would silently repoint every citation. Criteria 28–31 (freshness, the reuse bound, read-your-writes, one coherent moment) remain and hold at any size.

A plain-English walkthrough of the whole v1 service — resolution rule, module split, read/write paths, schema, surfaces — is at `docs/entitlement-service-explained.html`.
