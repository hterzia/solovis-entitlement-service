# Implementation Plan: Plain-English Checker

**Branch**: `003-natural-language-procesing` | **Date**: 2026-08-09, revised 2026-08-10 | **Spec**: [`spec.md`](./spec.md)
**Extends**: [`../001-entitlement-service/plan.md`](../001-entitlement-service/plan.md) — `entitlement-service` and `management-ui` only
**Reads against**: `main` as of `b1b795b` (the SQLite read-path merge)

## Summary

An ask box on the checker screen turns a typed question — *"Can Acme export parquet?"*, *"How many reports could Acme export last month?"* — into the triple the checker already takes (account, capability, optional date), runs the existing check, and renders the existing trace.

**This is a small feature and must stay one.** It is natural-language lookup over the database: one model call proposes what the sentence names, the service checks that against its own rows, and the existing checker answers. Nothing here computes an entitlement, caches anything, learns anything, or holds state between questions.

### What this feature is *not* — guard against gold-plating

| Not building | Because |
|---|---|
| An agent, tool-calling, or multi-turn anything | One call in, one proposal back. Spec §2: no conversation |
| Its own resolution, ranking, or scoring of entitlements | The checker answers; this only decides what to ask it |
| A cache, an embedding index, or a vector store | ~500 capability names in a prompt is a few KB. There is no retrieval problem here |
| A general date library or NL-date parser | The model returns a date; the service parses one ISO string |
| Retry, fallback models, or degraded interpretation | Any failure is 503. Convenience degrades; answers never do |
| Any change to `entitlement-core`, `entitlement-client`, the feed, or any decision path | Deleting this package must change no decision anywhere |

---

## Where `main` stands (verified 2026-08-10)

The **SQLite read-path change is merged** (`plan-read-path.md`, complete). This is the single biggest input to this plan, and it settles questions earlier drafts spent pages on.

| Fact on `main` | What it means here |
|---|---|
| `SnapshotHolder` and `SnapshotStartup` are **deleted**; the service holds no snapshot | Any plan text about reading the catalogue "from the snapshot" is void. There is no snapshot to read |
| `store/DecisionReadDao` is the one class on the read pool (`entitlementReadJdbcClient`) | **This is where both of the ask path's reads belong.** The repositories are on the single-connection *write* pool |
| `DecisionReadDao.account(externalId)` hardcodes `AND status = 'ACTIVE'` | The CLOSED-account convention is already established — the matcher must match it |
| `DecisionReadDao.allCapabilities(area, status, query)` exists | The catalogue query, already written |
| `MetaController` and `GlobalExceptionHandler` now take `DecisionReadDao`, not `SnapshotHolder` | Both of this plan's touchpoints there changed shape |
| `CheckerController.check(...)` gained a `noStore(...)` wrapper (c30) | Headers only — this plan takes `.getBody()`, so it is unaffected |
| The ask package's stale branch (`worktree-entitlement-ask-nlp`, `39af421`) is ~120 commits behind | Copy the files onto a fresh branch; never merge that branch |

**One known gap on `main`, not ours to fix**: `PlanAdminService.preview()` still calls `snapshotAssembler.assembleFull()` and overlays with `SnapshotMutator` — the one surviving production use, which `plan-read-path.md`'s own definition of done requires to be zero. Unrelated to this feature; noted so it is not mistaken for something 003 introduced.

## What 002 already shipped (verified on `main`, 2026-08-10)

**002 is not a future dependency — it is merged and complete, backend and frontend.** Every date assumption in this plan is now a fact that can be read out of the code. Nothing here is "assume it's done".

| Shipped | Where | Used by 003 for |
|---|---|---|
| `GET /admin/v1/check?…&asAt=YYYY-MM-DD` — **`asAt` is a `String`, parsed in the controller**, and the argument order is `check(account, capability, override, asAt)` | `admin/CheckerController` | The date path. This plan's earlier *guess* at that order was correct; no longer a guess |
| `AsAtCheckService`, `AsAtViewAssembler`, `AsAtDecisionResponseDto` | `admin/service/`, `snapshot/` | The past answer itself |
| `ErrorCode.FUTURE_DATE`, `BEFORE_ACCOUNT_EXISTED`, `BEYOND_HISTORY`, `INVALID_WINDOW` | `error/ErrorCode` | The three refusals — **delegated**, never reimplemented (c19) |
| `capabilityRetiredSince` on the as-at body | `AsAtDecisionResponseDto` | Retired-since-that-date answers normally (c20) |
| `OverrideStanding`, `StandingOverride`; DAO `inForceOverrides(…, asOf)` / `knownOverrides(…)` | `entitlement-core`, `DecisionReadDao` | Nothing directly — 003 never touches standing |
| `ClockConfig`'s **zone-carrying** `Clock`; `NoDirectClockAccessTest` | `service/time` | `LocalDate.now(clock)` is the Eastern date; the `ask` package must pass that test |
| **The checker screen's date field, the "Showing X, not today" banner, and `SERVICE_CLOCK = 'US Eastern'`** | `routes/checker/CheckerRoute.tsx` | The ask box sets `asAt`; all past-answer chrome already exists |
| `CheckParams.asAt`, `CheckResult extends Decision { asAt?, capabilityRetiredSince? }` | `api/checker.ts` | The SPA already types a past answer |
| `ERROR_MESSAGES` extended with the three 002 refusals | `CheckerRoute.tsx` | **Reuse it** — see §10 |

**Migrations on `main` are V1, V3, `V4__override_windows.sql`, and a V5 Java migration** (an `audit_event` CHECK-widening rebuild, registered as a `@Component` `JavaMigration` because it needs `PRAGMA foreign_keys=OFF`). The V2 slot conflict CLAUDE.md describes was resolved by moving to V4. **003 adds no migration** — do not create one.

**Branch off `main`.** The current working branch (`feat/fuller-demo-seed`) predates all of this — it has no V4, no standing types, and no as-at service.

---

## Technical Context

**Language/Version**: Java 21, Spring Boot 4.0.x; TypeScript 5.x / React 19. No new toolchains. Reactor root is `management/backend`; SPA is `management/frontend/management-ui`.

**New dependency**: `dev.langchain4j:langchain4j-google-ai-gemini:1.18.0`. `ChatModel` built directly (`GoogleAiGeminiChatModel.builder()`) — no LangChain4j Spring starter, no AiServices. Pin it the way this reactor pins everything else: a `${langchain4j.version}` property plus a `<dependencyManagement>` entry in `management/backend/pom.xml`, and no inline version in the module pom.

**Model**: `gemini-3.5-flash-lite`, `temperature(0.0)`. Verified 2026-08-09: correct on an interpreter-shaped probe in 0.51 s, paid tier, `serviceTier: "standard"`.

**Configuration** (`application.yaml`):

```yaml
entitlement:
  ask:
    api-key: "${GOOGLE_AI_GEMINI_API_KEY:}"   # blank ⇒ feature off, /check/ask answers 503
    model: gemini-3.5-flash-lite
    timeout: 5s
```

The key is **provisioned** in the git-ignored repo-root `.env`. Load with `set -a; source .env; set +a`. `timeout: 5s` is a ceiling on the failure path, not a target.

**Reference**: `refs/l4j-examples/google-ai-gemini-examples/` — Example05 is the JSON-schema pattern. Vendored read-only.

---

## Design

### 1. Package layout

```
ask/
├── AskService.java              interpret → verify → check → respond
├── AskController.java           POST /admin/v1/check/ask   (one line)
├── AskConfiguration.java        conditional interpreter bean + AskService bean
├── AskProperties.java           entitlement.ask.*
├── AskUnavailableException.java
├── QuestionInterpreter.java     Proposal interpret(question, catalog, today)
├── GeminiQuestionInterpreter.java
├── Proposal.java                accountMention, capabilityKeys, capabilityMention,
│                                dateMention, resolvedDate
├── CapabilityCatalog.java       record: entries of (key, area, displayName, retired)
├── CapabilityCatalogProvider.java   single method — a lambda in tests
├── DaoCapabilityCatalogProvider.java
├── AccountMatcher.java          single method — a lambda in tests
├── AccountMatch.java            sealed: One | Candidates | TooMany | None
├── DaoAccountMatcher.java
├── CheckerPort.java             explain(account, capability, asAt)
├── CheckerControllerPort.java
├── package-info.java
└── dto/{AskRequest, AskResponse}.java
```

Both provider interfaces carry exactly one method, so unit tests stub them with lambdas — which is what the existing tests already do (`mention -> new AccountMatch.None()`).

### 2. The one-way flow

```
question ─► AskService ─► QuestionInterpreter ─► Gemini
               │           (question, catalogue, today)  │
               │                   Proposal (untrusted) ◄┘
               ▼
        verify locally    catalogue keys · ACTIVE accounts · one ISO date parse
               │
               ▼
         CheckerPort ─► CheckerController ─► DecisionController ─► DecisionReadService
               │                                (asAt when present)
               ▼
          AskResponse
```

`GeminiQuestionInterpreter` holds a `ChatModel` and an `ObjectMapper` — no DAO, no view, no resolver, no `CheckerPort`, and **no `Clock`** (today's date is passed in, so it cannot even read the time). It cannot transmit what it cannot reach. `AskService` is the only class touching both sides, and the interpreter is called once, before any lookup, and never again — so it cannot see an outcome.

### 3. Both reads go through `DecisionReadDao`

The ask path reads two things: the capability catalogue, and accounts by name. Both belong on the **read pool**, because the repositories run on a deliberately single-connection write pool.

- **Catalogue** — `dao.allCapabilities(null, null, null)`, all statuses. Retired capabilities are included by name (spec §4) so a question about a past date can match one; the `retired` flag on each entry is what the service uses to decide meaning.
- **Accounts** — `DecisionReadDao` has no name search, so add one, mirroring `AccountRepository.search`'s SQL and carrying the DAO's own ACTIVE predicate:

```java
public List<AccountRow> searchAccounts(String q, int limit) {
    return jdbcClient.sql("""
            SELECT * FROM account
             WHERE status = 'ACTIVE'
               AND (external_id LIKE :q ESCAPE '\\' OR name LIKE :q ESCAPE '\\')
             ORDER BY id LIMIT :limit""")
        .param("q", SqlLike.contains(q)).param("limit", limit)
        .query(ACCOUNT_ROW_MAPPER).list();
}
```

**Why ACTIVE matters** (user decision 2026-08-10): `account.status` allows `ACTIVE` and `CLOSED`; `DecisionReadDao.account(...)` already filters, deliberately, so `/v1` keeps raising `unknown-account` for a closed account. `AccountRepository.search` does **not** filter — so matching through it would confidently resolve a closed account and then fail the check. A closed account reads as *"No account matching 'Acme'"*. When account closure actually ships, promote that to its own outcome so *closed* never collapses into *never heard of them*.

**Do not put a transaction around the ask path.** Every neighbouring class now carries `@Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)`, so copying it here looks right and is wrong: it would hold a SQLite read transaction open across a multi-second call to Gemini. The two reads are *advisory* — which capabilities exist, which account is meant — and the answer's coherence comes from the checker opening its own transaction downstream.

### 4. The interpretation call

One request, structured JSON (`ResponseFormat.JSON` + `JsonSchema`). System prompt = task + today's date + catalogue; user message = the question verbatim.

```jsonc
{
  "accountMention":    "string|null",   // the operator's words for the account
  "capabilityKeys":    ["string"],      // 0–3 catalogue keys, best first  (required)
  "capabilityMention": "string|null",   // words, so an empty list can still say what failed
  "dateMention":       "string|null",   // words for the time, e.g. "last month"
  "resolvedDate":      "string|null"    // YYYY-MM-DD, the one day those words mean
}
```

Date rules in the prompt: no time reference → both null; a reference naming one day → both set; a reference too vague to name a day → `dateMention` only; never invent a date the question does not imply.

**Any failure is total.** Transport, timeout, malformed JSON, schema violation — all wrapped in `AskUnavailableException` → 503. Log the cause at WARN before wrapping, so a real bug is diagnosable rather than merely "unavailable".

**Sort the catalogue by key** in `CapabilityCatalog.from(...)`. `allCapabilities` has no `ORDER BY`, and a prompt that varies run to run makes interpretation irreproducible.

### 5. Local verification, in full

This is the whole of the service's logic. It is deliberately a short method.

**Capability** — drop duplicates; drop keys absent from the catalogue (c10). None left → `NO_MATCH` naming `capabilityMention`. Several → `CLARIFY`. One → continue.

There is **no separate retired-capability lookup**. Retired capabilities are in the catalogue with a flag, so the surviving key already knows:

| Surviving key is retired, and… | Outcome |
|---|---|
| no date in the question | `RETIRED_CAPABILITY` — a statement, no check (c7) |
| a date in the question | Continue. 002 answers normally with `capabilityRetiredSince` (c20) |

*(This deletes the scaffold's `retiredMatch` method entirely — it existed only because the catalogue used to be active-only.)*

**Date** — no sealed types, no date library, one parse:

```java
String asAt = null;                       // the checker takes an ISO string, not a LocalDate
if (proposal.dateMention() != null || proposal.resolvedDate() != null) {
    LocalDate day = parseIsoOrNull(proposal.resolvedDate());
    if (day == null) {
        return AskResponse.noMatchDate(proposal.dateMention());   // c18 — vague, never rounded
    }
    asAt = day.toString();
}
```

The local parse exists only to separate *"the model could not pin this down"* from *"the model named a day"*. Once it has, the day goes to the checker as text and every judgement about whether that day is answerable stays there.

Absence of a date is never treated as a date (c16). **The three date refusals are delegated**: future, before-account-existed and beyond-history are 002's problem types. `AskService` does not pre-check them — it passes the date and lets `GlobalExceptionHandler` render 002's exact words (c19). One source of truth for what a valid date is. The only local check is parseability, because a malformed string is a broken *interpretation*, not a rejected date.

**Today's date** comes from the injected `Clock`: `LocalDate.now(clock)`. 002's bean carries the service zone, so that *is* the Eastern date, and the package passes `NoDirectClockAccessTest` because it never calls a bare `now()`.

**Account** — exact external id → `One`; else `searchAccounts(mention, 9)`; one exact case-insensitive name match → `One`; 0 → `None`; 1 → `One`; >8 → `TooMany`; 2–8 → `Candidates` (c6).

### 6. The checker seam

```java
public interface CheckerPort {
    /**
     * Exactly the body of GET /admin/v1/check. asAt null ⇒ about now.
     *
     * <p>Object, deliberately: the checker returns DecisionResponseDto for a present-tense
     * question and AsAtDecisionResponseDto for a past one. The two have no common Java
     * supertype, but AsAtDecisionResponseDto @JsonUnwraps the decision, so on the wire a past
     * answer is the ordinary shape plus asAt and capabilityRetiredSince. Ask carries the body
     * through untouched and never reads a field off it — so it needs no type, and inventing a
     * wrapper here would put a second description of the checker's payload in the codebase.
     */
    Object explain(String accountExternalId, String capabilityKey, String asAt);
}
```

```java
@Component
class CheckerControllerPort implements CheckerPort {
    private final CheckerController checker;
    public Object explain(String account, String capability, String asAt) {
        return checker.check(account, capability, null, asAt).getBody();
    }
}
```

**`Object` is not laziness here — narrowing it would be a bug.** An earlier revision of this plan listed "narrow `Object` to `DecisionResponseDto`" as a consistency fix, reasoning that the scaffold's `Object` was a placeholder for the unbuilt checker. It was, but the type is now correct for a reason that did not exist when it was written: a cast to `DecisionResponseDto` throws `ClassCastException` on **every past-dated question**. The same applies to `AskResponse.result` — it stays `Object`.

**`asAt` is a `String`, not a `LocalDate`.** `CheckerController` takes the raw parameter and parses it itself. 003 still parses locally first (§5) — a malformed date is a broken *interpretation*, which must read as `NO_MATCH`, not as the controller's `VALIDATION_FAILED` — and then passes the validated day back as an ISO string.

**Why `CheckerController`, not `DecisionController`**: criteria 1 and 15 name `GET /admin/v1/check`, and `CheckerController` *is* that endpoint. `DecisionController` is the `/v1` product API, which deliberately has **no `asAt`** (the past is an operator surface only), so it cannot serve the date path at all.

`CheckerController`'s `noStore(...)` wrapper touches headers only, and this takes `.getBody()`.

**Errors propagate.** The three §6.3 errors and 002's three date refusals all reach `GlobalExceptionHandler`. Swallowing any into `NO_MATCH` would turn *"we know, and the answer is X"* into *"we did not understand"* — the confusion c8 forbids.

### 7. Error model — three corrections to the scaffold

**(a)** Register the slug rather than hand-rolling a `ProblemDetail` in the controller — `ErrorCode` is documented as holding every one. **Append it last**, since 002 adds four of its own:

```java
ASK_UNAVAILABLE("entitlement/ask-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Ask unavailable"),
```

**(b)** Handle `AskUnavailableException` in `GlobalExceptionHandler`. `AskService.ask` already throws it when unavailable, so `AskController` collapses to:

```java
@PostMapping("/admin/v1/check/ask")
public AskResponse ask(@Valid @RequestBody AskRequest request) {
    return askService.ask(request.question());
}
```

**(c)** `AskControllerTest.rejectsABlankQuestion` asserts **400** — what a bare `standaloneSetup` gives. The repo maps validation failures to **422** / `entitlement/validation-failed`. Register the advice and assert 422. Note the handler now takes `DecisionReadDao`, so the test needs `mock(DecisionReadDao.class)` — it is only consulted for `/v1/` paths, and `/admin/v1/check/ask` is not one.

### 8. API contract

`POST /admin/v1/check/ask`, Role (future) Viewer. Request `{ "question": "…" }`, ≤500 characters.

One record, four statuses; Jackson is `non_null`, so each serialises only its own fields.

```jsonc
{ "status": "ANSWERED",
  "interpretation": { "account": {"external":"acme","name":"Acme Corp"},
                      "capability": "reports.monthly",
                      "asAt": "2026-07-15", "dateMention": "last month" },
  "result": { /* exactly the GET /admin/v1/check body */ } }

{ "status": "CLARIFY",
  "interpretation": { "accountMention": "Acme", "capability": "export.parquet",
                      "asAt": "2026-07-15", "dateMention": "last month" },
  "accountCandidates": [ … ] }          // capabilityCandidates likewise; both when both

{ "status": "NO_MATCH",
  "unmatched": { "dateMention": "recently" },
  "detail": "'recently' isn't a date I can pin down — give me a day." }

{ "status": "RETIRED_CAPABILITY", "interpretation": { "capability": "export.csv" },
  "detail": "Capability 'export.csv' is retired and no longer evaluable." }
```

503 (`entitlement/ask-unavailable`) and 422 (`entitlement/validation-failed`) are RFC 9457 problems — **not** the `{"error": "ASK_UNAVAILABLE"}` shape an earlier draft of this plan documented. 002's date refusals pass through untouched.

A `CLARIFY` carries the resolved date so a pick does not silently drop *"last month"*.

The four understanding outcomes are HTTP 200 with a `status` discriminator: each is a successful act of understanding-or-not. Only unavailability, malformed input and 002's date refusals are HTTP errors.

### 9. Feature discovery

`MetaResponseDto` gains `boolean askEnabled`; `MetaController` (which now takes `DecisionReadDao`) additionally injects `AskService` and reports `available()`. The SPA's `ServiceMeta`, the MSW meta handler and `api/meta.test.ts` update in step. This is the only way the UI learns the feature exists — it must never infer availability from a failed ask.

### 10. UI

`AskBox` mounts above the existing pickers in `CheckerRoute.tsx`.

**Follow the existing meta pattern**: there is no shared `useMeta` hook — five components each run an inline `useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })`. `AskBox` does the same.

**Everything the past needs already exists on this screen.** 002 shipped the `asAt` state, the `As at (US Eastern)` input, the *"Showing 2026-07-15 (US Eastern), not today"* banner, `SERVICE_CLOCK`, and `ERROR_MESSAGES` extended with its three refusals. 003 adds no past-answer chrome — it fills in a field.

Two things to **reuse rather than restate**:

- **`SERVICE_CLOCK`** in the ask box's date chip, so an interpreted date names the same clock as a typed one (002 c5).
- **`ERROR_MESSAGES`** for any refusal the ask endpoint returns. An operator must see the same words whether they asked in English or used the pickers; `AskBox` importing the map (or it moving to a small shared module) is the whole of that. Do not write a second wording for *"that date is in the future"*.

**Hand off; do not render the trace.** On `ANSWERED`, `AskBox` gives the triple to `CheckerRoute`, which sets its pickers and date field and runs its ordinary check query.

```tsx
<AskBox onResolved={(account, capability, asAt) => {
  setAccount(account); setCapability(capability); setAsAt(asAt ?? ''); setOverrideRef('')
  setSubmitted({ account, capability, asAt }); setCopyOutcome(null)
}} />
```

This keeps one trace renderer, puts the interpreted values where the operator can tweak them, and makes 002's "showing the past" banner fire for free. `AskResponse.result` therefore goes unrendered by the SPA — keep it (it proves c1/c15 at the API level and the payload-equality test asserts on it) and say so where it is defined, so it is not later deleted as dead weight.

| State | Rendering |
|---|---|
| `askEnabled === false` | Input disabled: *"Ask is unavailable — use the pickers below."* |
| pending | `role="status"`: *"Asking…"* |
| `ANSWERED` | Calls `onResolved`; chip *"Understood as: **Acme Corp** × **reports.monthly**, as at 15 July 2026"* — date in words, omitted entirely when none (c2, c17) |
| `CLARIFY` | Candidate buttons; a click calls `onResolved` with that pair **and the date** |
| `NO_MATCH` / `RETIRED_CAPABILITY` | The service's own `detail`, styled as a statement — never as a decision, never using *no* (c8) |
| 503 / 4xx | `ErrorNotice`, which already prints the service's own words, so 002's date refusals appear verbatim (c19) |

Use only `sv-*` / `app-*` classes from `.claude/design/solovis/tokens.css`.

**Sequencing**: `docs/superpowers/specs/2026-08-10-checker-input-suggestions-design.md` is approved-but-unimplemented and restructures the same component (datalists on both inputs, fieldsets around the two lookup modes). No semantic conflict, but do not build both in parallel — whichever lands second rebases onto the other.

**New file** `src/api/ask.ts` with `askQuestion(question)` over `apiPost`. `queryKeys` gains nothing — a mutation needs no key.

---

## Consistency fixes the port must carry

1. `pom.xml` — version to the parent's `dependencyManagement`, not inline.
2. `ErrorCode` — `ASK_UNAVAILABLE`, appended last.
3. `GlobalExceptionHandler` — handle `AskUnavailableException`; delete the controller's hand-rolled `ProblemDetail`.
4. `AskController` — one line; returns `AskResponse`.
5. `AskControllerTest` — advice registered (with `mock(DecisionReadDao.class)`); assert **422**.
6. `CheckerPort` / `AskResponse.result` — **keep `Object`**; drop both `TODO(003)`s, which propose a narrowing that would now break every past-dated question (§6).
7. Catalogue + matcher — move onto `DecisionReadDao`; delete the obsolete snapshot TODO; include retired entries; sort by key; **delete `retiredMatch`**.
8. `DecisionReadDao` — add `searchAccounts(q, limit)`, ACTIVE-filtered.
9. `askEnabled` in all five places: `MetaResponseDto`, `MetaController`, `ServiceMeta`, MSW handler, `api/meta.test.ts`.

---

## Testing

**Unit** (hand stubs / lambdas, no Spring):
`AskServiceTest` — the status branches; date branches (no date → null `asAt` reaches the port; a date → that `LocalDate` does; vague → `NO_MATCH`, port never called; unparseable → same; retired **with** a date → check runs; retired **without** → statement, port never called); the one-way flow (interpreter called exactly once with question + catalogue + today, never again); invented-key rejection.
`CapabilityCatalogTest` — rendering, area derivation, retired marking, key-sorted determinism.
`DaoAccountMatcherTest` — exact id, exact name, single, 2–8, >8, none, **and a CLOSED account that must not match**.

**MockMvc / Spring** (temp-file SQLite, one DB per JVM run):
503 and 422 shapes; **payload equality** — `$.result` equals `GET /check` for the same pair and `GET /check?…&asAt=` for the same triple, compared as parsed JSON; **date refusals pass through** with 002's own `type`; **no audit rows** across all four statuses; `askEnabled` false/true. `NoDirectClockAccessTest` green with the package present.

**Live** (`@EnabledIfEnvironmentVariable`; the key is provisioned, so these run):
canonical extraction; nonsense capability → empty keys; date extraction (*"last month"*, *"on 14 March"*, *"recently"* → mention with null date, no time words → both null); **wire-level confinement** — a `ChatModelListener` asserts the outbound body carries the question, catalogue keys and today's date and **no** account id, account name, plan key, override id, reason or value (c9's only mechanical proof, deferred twice already); p95 for c13 and c21.

**Frontend**: `AskBox.test.tsx` per state including the date chip and a `CLARIFY` pick preserving the date; `CheckerRoute.test.tsx` for the handoff, the disabled state, and `NO_MATCH` rendering distinctly from `allowed: false`. MSW gains `POST /check/ask` and `askEnabled`.

**c14** needs no k6 work — the endpoint shares no code or pool with any decision path; 001's load run remains the evidence.

---

## Implementation guide — task by task

One commit per task, test-first. Written so it can be followed without having read the rest of this repo first; when this guide and the code disagree, **the code wins — read it, then fix this guide**.

### Before you start

```bash
cd /path/to/solovis                      # repo root
git switch main && git pull
git switch -c 003-natural-language-procesing
# Bring this plan/spec with you if they are not on main yet
#   (as of 2026-08-10 they were uncommitted on feat/fuller-demo-seed):
git checkout feat/fuller-demo-seed -- .specs/003-natural-language-procesing
git commit -m "docs(003): carry the revised spec and plan onto the feature branch" \
  .specs/003-natural-language-procesing
```

Build/test loop (Maven reactor root is `management/backend`, **not** the repo root):

```bash
cd management/backend
./mvnw -pl entitlement-service -am test          # -am is REQUIRED (service depends on core)
./mvnw -pl entitlement-service -am test -Dtest='Ask*'       # just the ask tests
cd ../frontend/management-ui && npm run test     # frontend unit suite
```

The live Gemini tests need the key in the environment first:

```bash
cd management/backend
set -a; source ../../.env; set +a               # loads GOOGLE_AI_GEMINI_API_KEY (repo-root .env)
./mvnw -pl entitlement-service -am test -Dtest='GeminiQuestionInterpreterSmokeTest'
```

Without the key they **skip** (that's `@EnabledIfEnvironmentVariable` working) — a skip is not a pass; check the Surefire summary says `Tests run`, not `Skipped`, when you mean to run them live.

### House rules that will bite you if skipped

- **Never `git add -A`.** The working tree carries long-lived unrelated changes. Stage only the files your task names.
- **Match the indentation of the file you're editing.** `store/` and the ask scaffold use tabs; `admin/` controllers use 4 spaces. Don't reformat either.
- **Never call `Instant.now()` / `LocalDate.now()` bare.** Inject `java.time.Clock` and use `LocalDate.now(clock)`. `NoDirectClockAccessTest` fails the build otherwise.
- **No test yaml changes needed.** A blank `api-key` means no interpreter bean, which is exactly what every non-ask test wants. Don't add `entitlement.ask.*` to `src/test/resources/application.yaml`.
- **If e2e fails weirdly**, check nothing is squatting on port 8099 from an earlier run (`ss -tlnp | grep 8099`) before believing the failure.

### Phase 1 — port onto current `main`

**T1 — copy the scaffold.** The 946-line ask package lives on branch `worktree-entitlement-ask-nlp` (commit `39af421`), same directory layout as `main`:

```bash
git checkout worktree-entitlement-ask-nlp -- \
  management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/ask \
  management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/ask
```

Do **not** check out its `pom.xml` or `application.yaml` — they'd clobber `main`'s. Instead, by hand:

- `management/backend/pom.xml`: add `<langchain4j.version>1.18.0</langchain4j.version>` to `<properties>` and a `dependencyManagement` entry for `dev.langchain4j:langchain4j-google-ai-gemini:${langchain4j.version}` (copy the shape of the `sqlite-jdbc` entry beside it).
- `entitlement-service/pom.xml`: declare the dependency with **no version**, next to `springdoc`.
- `entitlement-service/src/main/resources/application.yaml`: add the `entitlement.ask.*` block from Technical Context, under the existing `entitlement:` key (beside `clock:`).

It will not compile yet — the scaffold references `DbCapabilityCatalogProvider`'s repositories fine, but the *tests* may drift. Get to green by the end of T3; committing a non-compiling tree mid-phase is fine only if you squash before review, so prefer: T1 = copy + pom + yaml, building with the two provider classes still repository-backed (they compile against `main` unchanged — verified). **DoD**: `./mvnw -pl entitlement-service -am test` green; this also settles that langchain4j 1.18.0 resolves.

**T2 — error model.** Four files:

1. `error/ErrorCode.java` — append before the closing constants block (it must stay a plain addition; the enum ends with `DUPLICATE_KEY`, add yours *before* it or re-terminate the semicolon correctly):
   `ASK_UNAVAILABLE("entitlement/ask-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Ask unavailable"),`
2. `error/GlobalExceptionHandler.java` — new handler beside `handleRetiredCapability`, same shape:
   ```java
   @ExceptionHandler(AskUnavailableException.class)
   public ResponseEntity<ProblemDetail> handleAskUnavailable(AskUnavailableException ex, HttpServletRequest request) {
       return respond(problem(ErrorCode.ASK_UNAVAILABLE,
           "The plain-English checker is not available right now; use the account and capability pickers.",
           request, Map.of()), request);
   }
   ```
3. `ask/AskController.java` — delete the guard, the try/catch and `unavailable()`; the method body becomes `return askService.ask(request.question());`, return type `AskResponse`.
4. `ask/AskControllerTest.java` — the handler now needs registering, and its constructor takes `DecisionReadDao` (only consulted for `/v1/` paths, so a Mockito mock with no stubbing is enough):
   ```java
   MockMvcBuilders.standaloneSetup(new AskController(unconfigured))
       .setControllerAdvice(new GlobalExceptionHandler(mock(DecisionReadDao.class)))
       .build();
   ```
   The blank-question test then asserts `status().isUnprocessableEntity()` (422) and `jsonPath("$.type").value("entitlement/validation-failed")` — **not** 400. Add a sibling test for a 501-character question, same assertions.

**DoD**: ask tests green; `grep -rn "ProblemDetail" src/main/java/.../ask/` → empty.

**T3 — both reads onto `DecisionReadDao`.**

1. `store/DecisionReadDao.java` — add (inside this class; `ACCOUNT_ROW_MAPPER` is a private static field here, which is why the method can't live anywhere else):
   ```java
   /** Ask's account-mention search. ACTIVE only, same reasoning as account(...) above. */
   public List<AccountRow> searchAccounts(String q, int limit) {
       return jdbcClient.sql("""
               SELECT * FROM account
                WHERE status = 'ACTIVE'
                  AND (external_id LIKE :q ESCAPE '\\' OR name LIKE :q ESCAPE '\\')
                ORDER BY id LIMIT :limit""")
           .param("q", SqlLike.contains(q)).param("limit", limit)
           .query(ACCOUNT_ROW_MAPPER).list();
   }
   ```
   `SqlLike` is in the same package. Test in `store/DecisionReadDaoTest` style: seed via the admin services, assert a CLOSED account is not returned. (No route closes an account yet — flip `status` with a direct `JdbcClient` update in the test, which the schema permits.)
2. Rename `DbAccountMatcher` → `DaoAccountMatcher`; constructor takes `DecisionReadDao`; `match()` uses `dao.account(mention)` for the exact-id step (already ACTIVE-filtered) and `dao.searchAccounts(mention, MAX_CANDIDATES + 1)` for the contains step. Logic otherwise identical to the scaffold.
3. Rename `DbCapabilityCatalogProvider` → `DaoCapabilityCatalogProvider`; constructor takes `DecisionReadDao`; `current()` returns `CapabilityCatalog.from(dao.allCapabilities(null, null, null))` — **all statuses now**, not just ACTIVE. **Delete `retiredMatch` from the provider and its interface.**
4. `CapabilityCatalog` — `Entry` gains `boolean retired` (from `row.status()` equals `"RETIRED"`); `from(...)` sorts by key; `render()` appends ` (retired)` to retired entries; add `Optional<Entry> find(String key)` beside `containsKey`.
5. Update `AskService.capabilityNotUnderstood` — the retired branch now reads from the catalogue (`find(key).map(Entry::retired)`) instead of `retiredMatch`; the full decision table is in §5.

**DoD**: `./mvnw -pl entitlement-service -am test` green; `grep -rn "retiredMatch\|CapabilityRepository\|AccountRepository" src/main/java/.../ask/` → empty (the ask package's only store import is `DecisionReadDao` + row records).

### Phase 2 — connect the checker

**T4 — `CheckerControllerPort`.** New file in `ask/`, exactly:

```java
@Component
class CheckerControllerPort implements CheckerPort {
    private final CheckerController checker;
    CheckerControllerPort(CheckerController checker) { this.checker = checker; }
    @Override
    public Object explain(String accountExternalId, String capabilityKey, String asAt) {
        return checker.check(accountExternalId, capabilityKey, null, asAt).getBody();
    }
}
```

`CheckerController.check`'s real signature on `main` is `check(String account, String capability, String override, String asAt)` — verified, not guessed. Change `CheckerPort` to the three-`String` signature above (delete its `TODO(003)` javadoc paragraph), and `AskConfiguration`'s `askService` bean to inject the port directly (`CheckerPort checker` parameter — it is a real bean now, drop its `ObjectProvider`; keep the interpreter's). `AskResponse.result` stays `Object` — see §6 for why narrowing it is a bug, and put that reason in its javadoc.

**T5 — the two MockMvc proofs.** One `@SpringBootTest` class (`AskEndToEndTest`), seeding through the admin services like `AsAtCheckTest` does:

- *Payload equality (c1/c15)*: register a `@TestConfiguration` `@Primary QuestionInterpreter` stub returning a fixed `Proposal`; `POST /admin/v1/check/ask`, read `$.result` as a `JsonNode`; `GET /admin/v1/check?account=…&capability=…`, read the whole body; `assertThat(askResult).isEqualTo(checkBody)`. Repeat with the stub proposing a date and `GET …&asAt=…`.
- *Audit silence (c11)*: `SELECT COUNT(*) FROM audit_event` before, run asks hitting all four statuses, count after, equal.

### Phase 3 — dates

**T6 — the proposal grows.** `Proposal` gains `String dateMention, String resolvedDate` (both nullable, in the record and the compact constructor's null-tolerance). `QuestionInterpreter.interpret` gains `LocalDate today`; `GeminiQuestionInterpreter` renders it into the system prompt (one line: `Today's date: 2026-08-10.` before the catalogue) and adds the two fields to `TASK_PROMPT`'s field list and to `PROPOSAL_FORMAT`'s schema (`.addStringProperty("dateMention").addStringProperty("resolvedDate")` — not required). The date rules for the prompt are in §4 of this plan, verbatim.

**T7 — `AskService` resolves the date.** Constructor gains `Clock` (from 002's zone-carrying bean — `LocalDate.now(clock)` **is** the Eastern date). At the top of `ask()`: `LocalDate today = LocalDate.now(clock)`, passed to `interpret`. After capability verification, the §5 parse-or-refuse block; `asAt` (a `String`) threads into `checker.explain(...)` and into every `AskResponse.Interpretation` (which gains `String asAt, String dateMention`). New factory `AskResponse.noMatchDate(mention)` producing `NO_MATCH` with `unmatched.dateMention` and the §8 detail text. The retired table from §5: `retired && asAt == null` → `RETIRED_CAPABILITY`, `retired && asAt != null` → proceed to the check.

Unit tests to add to `AskServiceTest`, each a stub-interpreter branch: no date → port receives `null`; a date → port receives `"2026-07-15"`; `dateMention` without `resolvedDate` → `NO_MATCH`, port never called; unparseable `resolvedDate` (`"July-ish"`) → same; retired key + date → port called; retired key, no date → `RETIRED_CAPABILITY`, port never called. Drive `today` with a fixed clock: `Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneId.of("America/New_York"))` — no Spring needed. (In `@SpringBootTest`s, the codebase's pattern is 002's `MutableClock` with `@TestConfiguration @Primary` — see `AsAtCheckTest`.)

**T8 — refusal pass-through test.** In `AskEndToEndTest`: stub proposes tomorrow's date → response is 422 with `$.type == "entitlement/future-date"` — 002's own words, untouched. One more with a date before the seeded account existed → `entitlement/before-account-existed`.

### Phase 4 — surface

**T9 — `askEnabled`.** Backend: `MetaResponseDto` gains `boolean askEnabled`; `MetaController` injects `AskService`, passes `askService.available()`. Frontend, all in one commit or the type-check breaks: `api/meta.ts` (`ServiceMeta` gains the field), `test/mocks/handlers.ts` (the meta handler near line 32 gains `askEnabled: true`), `api/meta.test.ts` (the `toEqual` gains it).

**T10 — `api/ask.ts` + `AskBox.tsx`.** New API module: the `AskResponse` interface (four-status union, `interpretation`, `accountCandidates`, `capabilityCandidates`, `unmatched`, `detail` — all optional except `status`), `askQuestion(question: string)` over `apiPost`. MSW: add `http.post('/admin/v1/check/ask', …)` beside the check handler (near line 281).

`AskBox` — props `{ onResolved(account: string, capability: string, asAt?: string): void }`; inline meta query (`useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })`, the same line five other components use); `useMutation({ mutationFn: askQuestion })`; the state table in §10. Import `SERVICE_CLOCK` and `ERROR_MESSAGES` from `CheckerRoute` (export them there — they are module-level `const`s today). Styling: existing `sv-label`/`sv-field`/`sv-btn`/`app-error` classes only.

**T11 — wire into `CheckerRoute`.** Mount `<AskBox onResolved={…}/>` above the form; the callback is four existing setters plus `setSubmitted` — the exact lines are in §10 of this plan, and the state variables (`account`, `capability`, `overrideRef`, `asAt`, `submitted`, `copyOutcome`) all already exist on `main`. Extend `CheckerRoute.test.tsx`: an answered ask fills the pickers *and* the date field and the trace renders; a `CLARIFY` click runs the classic check; `askEnabled: false` disables the box; a `NO_MATCH` shows no `allowed:` text anywhere.

**T12 — live proof.** Extend the smoke test with the date-extraction cases (§ Testing); add the `ChatModelListener` confinement test — build the listener into a test-constructed `GoogleAiGeminiChatModel` via `.listeners(List.of(capturing))`, ask a canonical question against a catalogue containing a marker key, then assert the captured request body contains the question + marker + today and none of: a seeded account id, account name, plan key, override id, reason string. Record p95 by timing the smoke calls (c13/c21) into the test log — no harness needed at this volume.

### If a task fights back

- **Bean-wiring failure on startup** (`NoSuchBeanDefinitionException: CheckerPort`): the port is package-private `@Component` — fine, but it must be in `service/ask/` (component scan covers it). If tests that exclude the ask package fail, they're constructing `AskService` by hand — pass the stub.
- **Jackson can't see `asAt` in a response**: Jackson is `non_null` globally; a null `asAt` is *supposed* to vanish. Assert absence with `jsonPath("$.interpretation.asAt").doesNotExist()`.
- **A `ClassCastException` from the port**: you re-added the `DecisionResponseDto` cast. Re-read §6.
- **`NoDirectClockAccessTest` red**: something in `ask/` calls a bare `now()`. The only permitted call is `LocalDate.now(clock)` in `AskService`.

---

## Risks

| Risk | Handling |
|---|---|
| **A date is misread** — the one interpretation step with no local record to check against | The resolved date is always displayed in words (c17) and correctable in the checker's own field. Recorded as a known limitation in spec §12 |
| **Copying the read-transaction annotation onto the ask path** | Would hold a SQLite read transaction across a multi-second model call. Called out in §3; the ask path stays untransacted by design |
| **Scope creep toward an "AI assistant"** | The *What this feature is not* table above is the guard. Any new capability here needs a spec change first |
| Retired capability names now leave the building | Names only, and they left as active names before retirement. Spec §7 updated; the wire test asserts the full contents |
| Today's date is a third thing sent | Non-sensitive by construction; spec §4 and §7 amended so the list stays exhaustive |
| Branch ~120 commits stale | Copy files onto a fresh branch; never merge it |
| `GeminiQuestionInterpreter` catches bare `Exception` | Log the cause at WARN before wrapping |
| Prompt injection | Nothing returned is trusted — keys against the catalogue, accounts against ACTIVE rows, dates against a parser. Worst case is `NO_MATCH` |
| Blank key mistaken for a broken feature | `askEnabled` and the box's own wording distinguish *off* from *broken* |

## Rollout

No key ⇒ no interpreter bean ⇒ 503 ⇒ `askEnabled: false` ⇒ the box disables itself. No new deployable, no schema change, no migration, no SDK impact, no audit rows. **All four phases can run back to back off `main`** — 002 and the read-path change are both merged, so nothing here waits on anything. The only external sequencing is the unimplemented checker-suggestions design, which touches the same component (§10).

## Files touched

```
management/backend/
├── pom.xml                                   + langchain4j.version + dependencyManagement
└── entitlement-service/
    ├── pom.xml                               + langchain4j-google-ai-gemini
    └── src/main/java/.../service/
        ├── ask/                              the package above (18 files)
        ├── store/DecisionReadDao.java        + searchAccounts(q, limit)
        ├── admin/MetaController.java         + askEnabled
        ├── admin/dto/MetaResponseDto.java    + askEnabled
        ├── error/ErrorCode.java              + ASK_UNAVAILABLE (last)
        └── error/GlobalExceptionHandler.java + AskUnavailableException
    └── src/main/resources/application.yaml   + entitlement.ask.*

management/frontend/management-ui/src/
├── api/ask.ts (+ test)                       new
├── api/meta.ts (+ test)                      + askEnabled
├── routes/checker/AskBox.tsx (+ test)        new
├── routes/checker/CheckerRoute.tsx (+ test)  mounts AskBox
└── test/mocks/handlers.ts                    + POST /check/ask, + askEnabled
```
