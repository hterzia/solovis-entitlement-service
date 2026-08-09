# Implementation Plan: Entitlement Service (v1)

**Branch**: `001-entitlement-service` | **Date**: 2026-08-09 | **Spec**: [`init-spec.md`](../init-spec.md) (deferred scope: [`future-spec.md`](../future-spec.md))

## Summary

One service answers, for any account and any capability, *are they allowed*, *what is the value*, and *how was that decided* — replacing the ad-hoc entitlement checks scattered across the product. It owns a capability registry, plan definitions, per-account GRANT/HOLD overrides, plan assignment, an append-only audit trail, and an operator UI.

The technical approach has one central idea: **the decision engine is a small pure library over an immutable in-memory snapshot, and that snapshot is replicated.** SQLite is the durable system of record and is never on a decision path. The Spring Boot management service holds the current snapshot in memory and swaps it atomically on every committed write; consuming product services embed the same engine via a Java SDK that keeps its own snapshot replica, refreshed from a delta feed. Because the SDK replicates the *model* (capabilities, plans, account→plan, overrides) rather than computed answers, decisions are local and sub-millisecond, deltas stay small, and a consuming product keeps answering from its last replica when the management service is down — which is exactly the outage posture fixed in spec §11.

**Explanations stay in the management layer.** Only the operator UI needs the §6.1 trace; every other service needs an answer. So `entitlement-core` exposes the one resolution rule through two entry points — `resolve()` returns `(allowed, value)` and allocates nothing, `explain()` layers the trace on top of the identical arithmetic. The service calls both; the replica feed and the SDK carry only what `resolve()` needs. This makes criterion 24 stronger rather than weaker: the trace is produced in exactly one place, by the only component holding the complete record. It also keeps commercially sensitive override reasons ("suspended pending investigation", "goodwill grant, renewal risk") out of every consuming service's memory and disk cache, which `future-spec.md` §16 warns about.

Because consumers hold a copy of the rule but never a trace, drift between replicas would be undiagnosable after the fact. It is therefore prevented up front: the feed carries a `resolverContract` version and a set of conformance vectors that each replica evaluates at startup, refusing to serve if its engine disagrees.

**One deliberate deviation from the spec, on the user's instruction:** operator authentication and the three roles of spec §9 are not built in v1 ("for now it's just me testing it out, we will add user compatibility later"). The service ships an `ActorResolver` seam with a development stub so that every audit record still carries an actor, but **acceptance criterion 37 (role separation) cannot be demonstrated by this plan.** Everything else in §10 is planned for.

## Technical Context

**Language/Version**: Java 21 (LTS, installed: OpenJDK 21.0.11) for `entitlement-core`, `entitlement-service`, `entitlement-client`; TypeScript 5.x on Node 22 (installed: v22.22.1) for the operator SPA.

**Primary Dependencies**: Spring Boot 4.0.x (`web`, `jdbc`, `validation`, `actuator`); Xerial `sqlite-jdbc`; Flyway (SQLite support); HikariCP; Jackson; springdoc-openapi. Client SDK: JDK `HttpClient` + Jackson only — no Spring, so any JVM service can embed it. UI: React 19 + Vite + TanStack Router + TanStack Query, styled from the existing `.claude/design/solovis/tokens.css`. Tests: JUnit 5, AssertJ, jqwik (property-based order-independence), Spring `MockMvc`; Vitest + Testing Library + Playwright; k6 via the `grafana/k6` Docker image (Docker 29.1.4 present; k6 is not installed natively).

**Storage**: SQLite in WAL mode, single file, single writer process — durable system of record for capabilities, plans, accounts, overrides, the append-only audit trail, and published snapshot versions. Every read on a decision path is served from an immutable in-process snapshot, never from SQLite.

**Testing**: JUnit 5 + AssertJ unit tests on `entitlement-core`; jqwik property tests for the order-independence guarantees (criteria 12, 13, 16); Spring Boot slice/integration tests against a temp-file SQLite database; SDK tests against a stubbed feed including outage and stale-resync paths; Vitest + Testing Library for SPA components, Playwright for the five screens; k6 for the volume demonstration (criteria 25–31) run with a concurrent write-churn generator.

**Target Platform**: Linux x86-64, JVM 21, one container per role. Management service: single instance (single SQLite writer). Consuming services: any number, each embedding the SDK with its own snapshot replica. Operator SPA is static assets served by the management service.

**Project Type**: Multi-module JVM monorepo (Maven reactor) — one pure library, one Spring Boot service, one distributable client library, one React SPA, one load-demonstration harness.

**Performance Goals**: 5,000 single-capability decisions/second sustained with p99 ≤ 10 ms; whole-account p99 ≤ 50 ms; both held **while plans and overrides are being written**; a committed change visible in decisions everywhere within 60 s end to end; callers reuse an answer for no longer than 10 s.

**Constraints**: SQLite permits exactly one writer host, so the management service does not horizontally scale — availability of the *decision* path comes from SDK replicas, not from service redundancy. Every decision resolves against exactly one snapshot version (one coherent moment, criterion 31). Explanations are produced only by the management service, so the operator UI is the estate's single diagnostic surface for "why". Capabilities are retired, never deleted; overrides are removed by soft-delete; the audit trail is append-only and enforced as such by SQLite triggers. No authentication and no role enforcement in v1 (user decision). No plan rollback and no override expiry (spec §8, §12). Value types must carry a total order, so `unlimited` is a distinct value and never a large number.

**Scale/Scope**: 100,000 accounts; an assumed ~500 capabilities across ~15 areas (the spec's twelve-region residency example implies sets are wide); ~10 plans; an assumed ~50,000 live overrides. Snapshot working set estimated at well under 100 MB; a 1 GB heap is comfortable. Audit retention 24 months minimum, append-only. Five operator screens.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

No constitution is defined for this project ("None defined"), so this gate is not evaluated and does not block Phase 0 or Phase 1.

- **All principles**: N-A — no governing principles document exists at the repository root or in `.specs/`. Nothing was checked, and nothing was waived by default.

**Post-Phase-1 re-check (Step 5): still N-A.** The Phase 1 design introduced no constitutional question because there is no constitution to answer to. The design was instead re-checked against the specification's own 41 acceptance criteria, which surfaced two things worth recording and nothing that blocks Phase 2:

- **Two readings that could reasonably go the other way**, now recorded as decisions in "Recorded interpretations" below rather than left implicit: the scope of criterion 21's trace requirement, and append-only tier ordering.
- **Criteria 25–31 are designed for but not yet evidenced.** The spec's definition of done requires a demonstration at 100,000 accounts and 5,000 decisions/second *against data changing during the demonstration*. The design makes those targets easy (decisions are in-memory map lookups against an immutably swapped snapshot), but "easy in principle" is not evidence — `entitlement-loadtest` exists as a module precisely so this is measured rather than asserted.
- **One risk the design creates and must answer for**: the resolution rule now runs in every consuming service, so two replicas on different SDK versions could disagree about the same account — the scattered-logic failure §1 exists to prevent, reappearing one layer down. It is dormant while §4's rule is frozen, and becomes live the moment `future-spec.md` §1 (time-bounded overrides) or §5 (relative grants) changes what "most generous" means. Answered by the `resolverContract` version and startup conformance vectors (`research.md` §20), not by discipline.

The one deviation from the specification, below, is unchanged by Phase 1.

### Accepted deviations from the specification

*Recorded here rather than in Complexity Tracking, which the template reserves for constitution violations. There are none of those.*

| Spec requirement | Deviation | Authority | Consequence |
|---|---|---|---|
| §9 roles (Administrator / Exception manager / Viewer) and criterion 37 | Not implemented in v1. An `ActorResolver` seam supplies a configured development identity so audit records remain complete; every endpoint is open. | User decision, 2026-08-09: "for now it's just me testing it out, we will add user compatibility later" | Criterion 37 is not demonstrable. The service must not be exposed beyond a trusted network until authentication lands. Criteria 32/36 still hold, but the recorded actor is a stub identity rather than a verified one. |

### Recorded interpretations of the specification

*Readings that a reasonable person could take differently. Stated so they are decisions rather than assumptions.*

| Spec text | Interpretation | Basis |
|---|---|---|
| Criterion 21, "every single-capability decision carries a trace", and §6.1 | This binds **the §6 evaluation interface** — the service's `GET /v1/accounts/{id}/capabilities/{key}`, which always returns a full trace. It does **not** bind the client SDK, which is a distribution mechanism for the outage posture rather than an instance of the evaluation interface. SDK decisions return `(allowed, value)` only; a consumer needing an explanation calls the service. | User decision, 2026-08-09: "only the main management/storage layer ui needs the explanation (§6.1), the rest of the services just need an answer." Criterion 21 remains demonstrable against the service endpoint, and criterion 24 is strengthened — there is now exactly one component that can produce a trace. |
| §4 tier ordering and criterion 3 | A capability's tiers may be **appended** above the current maximum ordinal but never inserted between existing ones. The spec requires a declared, caller-visible order but is silent on editing it. | Inserting renumbers ordinals and silently changes the meaning of values already stored in plans and overrides. Append-only is the only reading that does not rewrite the past. |
| §4 "most generous" / "most restrictive" when overrides tie on the deciding value | The trace marks the override with the **highest id — the newest — as the winner**. The effective value is identical whichever is marked; creation order appears nowhere in value resolution and labels the trace only. | User decision, 2026-08-09. Without a deterministic tie-break, two evaluations of identical state could render different explanations, and the property tests of `research.md` §19 — shuffle the overrides, assert identical results — could not cover traces. |

## Project Structure

### Documentation (this feature)

```
.specs/                                 # all specification material lives here
├── init-spec.md            # the v1 business specification (source of truth)
├── future-spec.md          # deliberately deferred scope
└── 001-entitlement-service/
    ├── plan.md             # this file
    ├── research.md         # Phase 0 — every technical decision, with alternatives
    ├── data-model.md       # Phase 1 — logical model, validation rules, SQLite DDL
    └── contracts/
        ├── README.md          # conventions: value encoding, error model, versioning
        ├── decision-api.md    # §6 product-facing evaluation REST API
        ├── snapshot-feed.md   # replication feed consumed by SDK replicas
        ├── admin-api.md       # REST API behind the operator SPA
        ├── java-client-sdk.md # SDK public surface + caller obligations (§7, §11)
        └── ui-screens.md      # the five §9 screens as UI contracts
```

Implementation-plan artifacts sit inside `.specs/` beside the business specification they derive from, one directory per feature slug. There is no top-level `specs/` directory.

### Source Code (repository root)

```
pom.xml                                    # Maven reactor: core, service, client, loadtest
mvnw / mvnw.cmd / .mvn/                    # wrapper — no Maven on this host

entitlement-core/                          # pure Java, no Spring, no I/O
└── src/main/java/com/solovis/entitlement/core/
    ├── model/                             CapabilityKey, ValueType, EntitlementValue (sealed),
    │                                      TierOrder, OffValue, Capability, Plan, PlanEntitlement,
    │                                      Override, OverrideKind, AccountAssignment
    ├── order/                             Generosity (total order per value type), ValueComparator
    ├── engine/                             Resolver.resolve() → Decision(allowed, value), allocation-free
    │                                      Resolver.explain() → Explanation(decision, trace)
    │                                      Trace, TraceEntry, TraceSource, Outcome
    ├── view/                              EntitlementView, Snapshot (immutable), SnapshotBuilder,
    │                                      SnapshotMutator (structural sharing)
    ├── conformance/                       ConformanceVector, ConformanceCheck, RESOLVER_CONTRACT
    └── error/                             UnknownAccountException, UnknownCapabilityException,
                                           RetiredCapabilityException

entitlement-service/                       # Spring Boot 4 — management + storage layer + UI host
├── src/main/java/com/solovis/entitlement/service/
│   ├── EntitlementServiceApplication.java
│   ├── api/                               DecisionController, SnapshotFeedController + dto/
│   ├── admin/                             CapabilityAdminController, PlanAdminController,
│   │                                      AccountAdminController, OverrideAdminController,
│   │                                      CheckerController, AuditController, SettingsController + dto/
│   ├── store/                             JdbcClient repositories (Capability, Plan, Account,
│   │                                      Override, Audit, SnapshotVersion)
│   ├── snapshot/                          SnapshotHolder (AtomicReference), SnapshotAssembler,
│   │                                      SnapshotPublisher, DeltaFeedService
│   ├── audit/                             AuditRecorder, ActorResolver (seam), StubActorResolver
│   ├── config/                            SqliteConfig, JacksonConfig, WebConfig (SPA fallback),
│   │                                      OpenApiConfig, ThreadingConfig
│   ├── error/                             GlobalExceptionHandler, ErrorCode
│   └── seed/                              DemoDataSeeder (100k accounts for the volume demo)
├── src/main/resources/
│   ├── db/migration/V1__baseline.sql
│   ├── application.yaml
│   └── static/                            built SPA assets (copied from entitlement-ui/dist)
└── src/test/java/...                      slice + integration tests, acceptance-criteria tests

entitlement-client/                        # the Java SDK products embed
└── src/main/java/com/solovis/entitlement/client/
    ├── EntitlementClient.java             public interface: check(), checkAll(), explain()
    ├── EntitlementClientBuilder.java
    ├── ReplicatedEntitlementClient.java   local Resolver.resolve() over the replica — no traces
    ├── SnapshotReplica.java               AtomicReference<Snapshot> + staleness accounting
    ├── SnapshotPoller.java                version poll, delta fetch, backoff, full resync
    ├── SnapshotDiskCache.java             survives a caller restart during an outage
    ├── ConformanceGate.java               runs the feed's vectors at startup; refuses on mismatch
    └── ClientHealth.java                  stale flag, snapshot age, last successful sync

entitlement-ui/                            # React SPA, built by Vite
├── package.json / vite.config.ts
└── src/
    ├── routes/                            capabilities/, plans/, accounts/, checker/, history/
    ├── api/                               typed REST client generated from the OpenAPI document
    ├── components/                        TraceView, ValueEditor, CapabilityTree (group/collapse/
    │                                      search), AffectedAccountsBanner, LivenessNotice
    └── styles/tokens.css                  imported from .claude/design/solovis/tokens.css

entitlement-loadtest/
├── k6/decision-single.js                  criterion 25
├── k6/whole-account.js                    criterion 26
├── k6/churn-writer.js                     concurrent mutation for criterion 27
├── k6/freshness-probe.js                  criteria 28-29
└── scripts/run-demo.sh                    orchestrates seed → churn → load → report
```

**Structure Decision**: The split exists to serve one requirement that everything else follows from — a decision must be answerable *inside a consuming service, from a local replica, while the management service is unavailable*. That is only honest if the code producing the answer is literally the same code in both places, so `entitlement-core` is a dependency-free library holding the domain model, the total order over values, the resolver and the trace. `entitlement-service` adds persistence, mutation, audit and the operator API around it; `entitlement-client` adds replication and staleness accounting around it. Neither owns any resolution logic, which is what makes criteria 20 and 24 structural rather than aspirational.

`entitlement-ui` is a separate Vite build whose output is copied into the service's static resources, so there is one deployable artifact and no CORS story, while the front end keeps its own toolchain. `entitlement-loadtest` is separate because criteria 25–31 must be demonstrated against a running deployment with data changing underneath it, not from inside the test suite.

## Complexity Tracking

> Fill ONLY if the Constitution Check has violations that must be justified.

No constitution is defined, so there are no violations to justify. This table is intentionally empty; the one accepted departure from the *specification* (operator roles) is recorded under Constitution Check above, where it will not be mistaken for a waived principle.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| — | — | — |
