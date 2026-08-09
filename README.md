reqs not read -- not sure if this is really necessary since i did read the requirements :P

# Solovis Entitlement Service

One service that answers, for any account and any capability: **are they allowed**, **what is the
value**, and **how was that decided** — replacing the ad-hoc entitlement checks scattered across a
product.

- **Live demo:** `<CLOUD_RUN_URL>` — operator UI at `/`, API docs at `/swagger-ui.html`
- **Architectural choices, alternatives rejected, scope deliberately cut:** [`DECISIONS.md`](./DECISIONS.md)
- **The specification that governs all of it:** [`.specs/001-entitlement-service/spec.md`](./.specs/001-entitlement-service/spec.md)

The demo is seeded with plans, capabilities, accounts, overrides and change history, so every screen
has something to show on first load.

---

## The shape of it, in 60 seconds

```
  operator UI ──▶ management service ──▶ SQLite (system of record)
                        │                    │
                        │  writes commit ────┘
                        │  and swap an immutable in-memory snapshot
                        │
                        └── snapshot feed ──▶ consuming product
                                                  └─ SDK: own snapshot replica
                                                     + the SAME resolver, run locally
```

Three modules, and the split exists to serve one requirement — a decision must be answerable *inside
a consuming product, from a local replica, while the management service is down*:

| Module | What it is |
|---|---|
| `entitlement-core` | Pure Java. No Spring, no I/O. The domain model, the total order over values, the resolver, the trace, the immutable snapshot. Both other modules depend on it; neither reimplements any resolution logic. |
| `entitlement-service` | Spring Boot app. SQLite system of record, mutations + audit, admin/decision/feed REST APIs, and it serves the built SPA as static assets — one deployable, no CORS story. |
| `entitlement-client` | The SDK products embed. JDK `HttpClient` + Jackson only, so any JVM service can use it. |

Two consequences worth knowing before reading any code:

- **SQLite is never on a decision path.** Reads resolve against an immutable in-memory snapshot,
  swapped atomically after each committed write.
- **Explanations exist only in the management service.** `Resolver.resolve()` returns
  `(allowed, value)`; `Resolver.explain()` runs the *identical* arithmetic and layers a trace on
  top, so the two cannot disagree. The SDK never traces — override reason text must not reach
  consuming products.

## Where to start reading

| If you want… | Read |
|---|---|
| What the product must do, and why | `.specs/001-entitlement-service/spec.md` — 41 numbered acceptance criteria, referenced from code as `(cNN)` |
| Every technical decision with its rejected alternatives | `.specs/001-entitlement-service/research.md` |
| How the pieces fit | `.specs/001-entitlement-service/plan.md` |
| The schema and validation rules | `.specs/001-entitlement-service/data-model.md` |
| The wire contracts | `.specs/001-entitlement-service/contracts/` — start with `README.md` (shared value/error vocabulary) |
| What was deliberately left out, and what should trigger building it | `.specs/future-spec.md` |
| The stretch goal (time-bounded overrides, point-in-time answers) | `.specs/002-time-bound-override/` |
| Conventions and traps for the next person | `CLAUDE.md` |
| How this was actually built — every prompt I typed, in order | [`docs/transcripts/`](./docs/transcripts/) |

## Running it locally

Prerequisites: **JDK 21**, **Node 22**. Maven comes from the wrapper; no database to install.

The Maven reactor root is `management/backend`, **not** the repository root.

```bash
# backend — http://localhost:8081
cd management/backend
./mvnw spring-boot:run -pl entitlement-service -am
```

`-am` is required: `entitlement-service` depends on `entitlement-core`, and without it the build
fails with *"Could not find artifact entitlement-core"*.

```bash
# frontend dev server — http://localhost:5173, proxying the API to :8081
cd management/frontend/management-ui
npm install
npm run dev
```

For a single artefact (what the deployed demo runs), build the SPA and let the service host it:

```bash
cd management/frontend/management-ui && npm run build
cp -r dist/* ../../backend/entitlement-service/src/main/resources/static/
cd ../../backend && ./mvnw -pl entitlement-service -am package
java -jar entitlement-service/target/entitlement-service-*.jar   # UI and API both on :8081
```

Useful endpoints once it is up:

| URL | |
|---|---|
| `http://localhost:8081/` | operator UI |
| `http://localhost:8081/swagger-ui.html` | API browser |
| `http://localhost:8081/actuator/health` | liveness |

## The three API surfaces

They are deliberately separate, because they have different audiences and different obligations.

| Prefix | For | Obligation |
|---|---|---|
| `/v1/**` | products asking for a decision | p99 ≤ 10 ms under sustained load, held while writes are happening |
| `/admin/v1/**` | the operator UI | correctness and auditability; every write records actor, before/after and reason |
| `/v1/snapshot/**` | SDK replicas | small deltas, a contract version, and conformance vectors |

The decision every other requirement exists to serve — with the explanation attached:

```bash
curl localhost:8081/v1/accounts/acct_9931/capabilities/reports.monthly
```

```jsonc
{
  "allowed": true,
  "value": { "type": "QUANTITY", "amount": 0 },     // suspended, not exhausted — the trace says which
  "snapshotVersion": 48211,
  "trace": {
    "baseline":  { "source": "PLAN", "planKey": "pro", "value": { "amount": 50 } },
    "grants":    [ { "overrideId": "ovr_4471", "value": { "amount": 200 }, "outcome": "WON" },
                   { "overrideId": "ovr_2210", "value": { "amount": 120 },
                     "outcome": "LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT" } ],
    "grantStep": { "applied": true, "winner": "ovr_4471", "value": { "amount": 200 } },
    "holds":     [ { "overrideId": "ovr_7788", "value": { "amount": 0 },
                     "reason": "Suspended pending billing investigation", "outcome": "WON" } ],
    "holdStep":  { "applied": true, "winner": "ovr_7788", "value": { "amount": 0 } },
    "result":    { "value": { "amount": 0 }, "allowed": true,
                   "allowedReason": "NO_OFF_VALUE_DECLARED" }
  }
}
```

Losers are listed with the reason they lost, so a denial is explained as fully as a grant. An
unknown account, an unknown capability and a retired capability are three distinct errors, never a
silent `false` — "we don't know" and "no" are different answers.

## Tests

```bash
cd management/backend
./mvnw test                              # whole reactor
./mvnw -pl entitlement-core test         # core only — no dependencies, fast
./mvnw -pl entitlement-service -am test  # -am required, as above
./mvnw -pl entitlement-core test -Dtest=ResolverResolveTest#methodName
```

JUnit 5 + AssertJ; **jqwik** property tests for the order-independence guarantees (shuffle the
override set, assert an identical decision); Spring tests run against a temp-file SQLite database —
no Testcontainers, no H2. The specification's worked-examples table is transcribed literally as a
parameterised fixture, and the same fixture is reused as the conformance vectors every replica
evaluates at startup.

Frontend: `npm run lint` (oxlint), `npm run build` (`tsc -b && vite build`).

## Configuration

| Variable | Default | |
|---|---|---|
| `ENTITLEMENT_DB_PATH` | `./data/entitlement.db` | SQLite file, relative to the service module |
| `SERVER_PORT` | `8081` | the app binds `0.0.0.0`, so it is reachable from another host |

The database is created and migrated (Flyway) on first start. Deleting the file and restarting gives
you a clean, freshly seeded environment; the seeder skips a database that already has data, so a
restart never overwrites changes you made through the UI.

## Repository layout

```
.specs/                     specifications and plans — the source of truth (see the table above)
DECISIONS.md                architectural choices, trade-offs, and what is out of scope
CLAUDE.md                   conventions, traps, and the current build state
docs/superpowers/plans/     task-by-task implementation plans, executed one commit per task
docs/superpowers/specs/     the GCP hosting design
docs/transcripts/           every prompt used to build this, in order
management/backend/         Maven reactor: entitlement-core, entitlement-service, entitlement-client
management/frontend/        the operator SPA (React + Vite), built into the service's static assets
refs/                       vendored read-only reference checkout; not part of the build
```

## Known gaps

Deliberate, not accidental — the reasoning for each is in [`DECISIONS.md`](./DECISIONS.md) §7 and the
trigger for building it is in `.specs/future-spec.md`.

- **No authentication and no operator roles.** Every endpoint is open, so anyone who can reach the
  service can add or lift any hold. Acceptance criterion 37 is not demonstrable. The hosted demo
  therefore carries synthetic data only and is torn down when the assessment concludes.
- **No usage counting.** This service owns the limit, not the counter.
- **No override expiry, no plan rollback, no relative grants, no bulk overrides.**
- **The load demonstration is designed for but not yet run.** The performance targets follow from
  in-memory resolution behind an atomic reference, but that is an argument, not evidence.
