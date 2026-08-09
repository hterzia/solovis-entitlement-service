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
./mvnw spring-boot:run -pl entitlement-service -am                   # http://172.17.192.221:8081
```

The app already binds `0.0.0.0:8081` (`application.yaml`) — this box is headless, always give the LAN URL, never `localhost`. Swagger UI at `/swagger-ui.html`. DB file defaults to `management/backend/entitlement-service/data/entitlement.db` (`ENTITLEMENT_DB_PATH` overrides).

Frontend (`management/frontend/management-ui`, still the bare Vite scaffold; `node_modules` not installed):

```bash
npm install
npm run dev      # add --host 0.0.0.0 or set server.host in vite.config.ts — not yet configured
npm run build    # tsc -b && vite build
npm run lint     # oxlint
```

## Architecture

The whole design follows from one requirement: **a decision must be answerable inside a consuming product, from a local replica, while the management service is down** (spec §11). That forces the resolution rule to be the *same code* in both places, which is why the reactor splits three ways:

- **`entitlement-core`** — pure Java 21, no Spring, no I/O. Domain model, the total order over values, the resolver, the trace, the immutable snapshot. Both other modules depend on it; neither reimplements any resolution logic.
- **`entitlement-service`** — Spring Boot 4 app. SQLite system of record, mutation + audit, admin/decision/feed REST APIs, hosts the built SPA as static assets.
- **`entitlement-client`** — SDK products embed. JDK `HttpClient` + Jackson only (no Spring) so any JVM service can use it. Currently a stub.

Load-bearing consequences:

- **SQLite is never on a decision path.** Reads resolve against an immutable in-memory `Snapshot`, swapped atomically (`AtomicReference`) after each committed write. `SnapshotMutator` produces the next snapshot by structural sharing.
- **Replicas carry the *model*, not computed answers** — capabilities, plans, account→plan, overrides. Decisions are local and sub-millisecond; deltas stay small.
- **Explanations exist only in the management service.** `Resolver.resolve()` returns `(allowed, value)` and allocates nothing; `Resolver.explain()` runs the *identical* arithmetic and layers a `Trace` on top, so the two cannot disagree. The SDK never traces — override reason text ("suspended pending investigation") must not reach consuming services.
- **Replica drift is guarded structurally**, not by discipline: the feed carries a `format` version, a `resolverContract` version (`core/conformance/ResolverContract.VERSION`), and conformance vectors each replica evaluates at startup, refusing to serve on mismatch. Bump `resolverContract` whenever §4's combining rule changes.
- **SQLite permits one writer host**, so the management service does not horizontally scale. Decision-path availability comes from SDK replicas, not service redundancy.

### The resolution rule (spec §4) — the thing everything protects

Baseline (plan entitlement, else capability default) → raised by the **most generous GRANT** → capped by the **most restrictive HOLD**. A restriction always defeats a concession. Order of overrides never affects the result (property-tested with jqwik). Value types are `SWITCH`, `QUANTITY`, `TIER`; `unlimited` is a **distinct variant, never a large number**, and tiers carry a declared total order.

## Spec-first workflow

```
.specs/                                 # one self-contained folder per feature; no top-level specs/
├── future-spec.md                      # deliberately deferred scope, with triggers and dependencies
├── 001-entitlement-service/            # v1: spec.md (source of truth, 41 acceptance criteria),
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
- The working tree carries long-lived unrelated pending changes (`.specs/**`, `DECISIONS.md`, deleted `homepage.html`, untracked `refs/`). **Never `git add -A`** — stage only the files the current task names.
- `refs/l4j-examples` is a vendored read-only reference checkout (LangChain4j examples). Not part of the build; do not modify or lint it.

## Conventions and traps

- **Package root** `com.solovis.entitlement.{core,service,client}`. Repositories and `*Row` records live in `service/store/`; empty `package-info.java` files mark packages the plans will fill.
- **No JPA/Hibernate.** Hand-written SQL through Spring's `JdbcClient`, explicit `RowMapper` lambdas onto `record` row shapes.
- **Two datasources over one file**: inject `@Qualifier("entitlementWriteJdbcClient")` (pool size 1, Flyway migrates through it) or `entitlementReadJdbcClient`. Every repository method today uses the *write* client on purpose — routing a read to the read pool can break read-your-writes (c30) without a service-layer transaction boundary to reason about.
- **`sql-error-codes.xml` in service resources is load-bearing.** Xerial sqlite-jdbc returns `sqlState=null` on every constraint violation, so without it *all* violations surface as `UncategorizedSQLException` instead of `DataIntegrityViolationException`.
- **Timestamps** are ISO-8601 UTC text with milliseconds, always computed in Java and passed in — never `datetime('now')` inside SQL. Inject `java.time.Clock`; never call `Instant.now()` directly in a controller or service.
- **`capability.area` is always derived** from the key (substring before the first `.`), never taken from a caller.
- **Immutability rules enforced by schema/triggers**: the audit trail is append-only; capabilities are retired, never deleted; overrides are never edited (remove + recreate, both with reasons); tiers may be appended above the max ordinal but never inserted between existing ones (renumbering would silently rewrite stored values); exactly one default plan.
- **The three §6.3 errors are errors, never denials** — unknown account, unknown capability, retired capability each get their own status and `entitlement/*` slug. "We don't know" and "no" are different answers (c19). Error model is RFC 9457; callers branch on `type`, never on message text.
- **Wire vocabulary is defined once** and reused by every surface (`contracts/README.md`): the three-variant value encoding, the capability descriptor, the error model. Don't redefine a `ValueDto` per package.
- **Tests**: JUnit 5 + AssertJ; jqwik for order-independence properties; Spring tests run against a temp-file SQLite DB (`src/test/resources/application.yaml`, one file per JVM run) — no Testcontainers, no H2.

## Deliberate v1 absences — do not "fix" these

- **No authentication and no roles** (user decision). Every endpoint is open; an `ActorResolver` seam supplies a stub identity so audit records stay complete. Acceptance criterion 37 is not demonstrable. The service must not leave a trusted network until sign-in lands.
- No override expiry, no plan rollback, no plan versioning, no `parentPlanKey`, no override edit route, no capability delete, no usage tracking (this service owns the *limit*, not the counter).
- Ungated capability default / off-value edits (accepted limitation, spec §12).
- Every deferral is documented with its trigger in `.specs/future-spec.md` — check there before proposing one.

## UI

`.claude/design/solovis/tokens.css` is the authoritative design source (extracted from Solovis production, not reconstructed). No colour, radius, spacing or type value may be introduced that isn't one of its custom properties or `.sv-*` classes. Use the `solovis-designer` agent for any Solovis-facing visual output. The SPA builds to Vite `dist` and is copied into the service's `static/` — one deployable, no CORS story.

Every mutation confirmation reads **"Saved. Active everywhere within {N} seconds."** with `{N}` from `GET /admin/v1/meta`, never a hard-coded 60 (c41).

## Current state

Built and tested: `entitlement-core` (model, order, resolver + explain, snapshot, mutator, conformance) and `entitlement-service/store` (dual-pool SQLite config, `V1__baseline.sql`, one repository per table). Not yet built: the service's `api/`, `admin/`, `snapshot/`, `audit/`, `error/`, `seed/` packages (plan: `docs/superpowers/plans/2026-08-09-entitlement-service-api-layer.md`), `entitlement-client`, the operator SPA (`.specs/001-entitlement-service/frontend-plan.md`, in progress in the `worktree-entitlement-ui-frontend` worktree), and `entitlement-loadtest`.
