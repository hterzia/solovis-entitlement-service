# Implementation Plan: Plain-English Checker

**Branch**: `003-natural-language-procesing` | **Date**: 2026-08-09 | **Spec**: [`spec.md`](./spec.md)
**Extends**: the entitlement service of [`../001-entitlement-service/plan.md`](../001-entitlement-service/plan.md) — `entitlement-service` and `entitlement-ui` only

## Summary

An ask box on the checker screen turns a typed question — *"Can Acme export parquet?"* — into the pair the checker already takes (account, capability), runs the existing `Resolver.explain()` path, and renders the existing trace. The language model does exactly one job: propose which account mention and which capability the question contains. It never computes, summarises, or sees an answer.

The technical shape follows from spec §4's one-way rule: a single `QuestionInterpreter` seam whose implementation calls the **Gemini Developer API** through LangChain4j, receiving only the question text and the capability catalogue, returning a proposal the service then verifies against its own records before anything runs. No change to `entitlement-core`, `entitlement-client`, the snapshot feed, or any decision path.

**Why Gemini Developer API rather than Vertex AI** (user decision 2026-08-09: "gcp, either gemini or vertex, whichever is best and easier"): identical Gemini models either way; the Developer API needs one API key while Vertex needs a GCP project, ADC and region wiring. `langchain4j-google-ai-gemini` is the lighter dependency and the reference examples in `refs/l4j-examples/google-ai-gemini-examples` cover exactly the structured-JSON pattern this feature needs (`Example05_ChatWithJsonResponse`). Paid-tier Developer API terms exclude use of submitted text for training — verify this remains true when the key is provisioned (spec §7).

## Technical Context

**Language/Version**: Java 21, Spring Boot 4.0.x (`entitlement-service`); TypeScript 5.x / React 19 (`entitlement-ui`) — no new toolchains.

**New dependency**: `dev.langchain4j:langchain4j-google-ai-gemini:1.18.0` (plus its transitive `langchain4j-core`). The `ChatModel` bean is constructed directly (`GoogleAiGeminiChatModel.builder()`); no LangChain4j Spring starter, no AiServices — one model call with a JSON response schema does not justify the abstraction.

**Model**: `gemini-3.5-flash-lite` — interpretation is a small extraction task; the cheapest, fastest Gemini tier fits the 3-second target (spec §8) with margin. Model name is configuration, not code. (The reference examples pin `gemini-2.5-flash-lite`, which the API now rejects for newly created projects — verified 2026-08-09 with the provisioned key: `gemini-3.5-flash-lite` answered the interpreter-shaped probe correctly in 0.51 s on the paid tier, `serviceTier: "standard"`, which also satisfies spec §7's no-training terms.)

**Configuration**:

```yaml
entitlement:
  ask:
    api-key: ${GOOGLE_AI_GEMINI_API_KEY:}   # absent ⇒ feature disabled
    model: gemini-3.5-flash-lite
    timeout: 5s
```

The key itself lives in the git-ignored `.env` at the repository root (`GOOGLE_AI_GEMINI_API_KEY=…`, mode 600), loaded with `set -a; source .env; set +a` before running the service or the smoke test — never in `application.yaml` and never committed. An empty key means the interpreter bean is not created, `POST /admin/v1/check/ask` answers `503 ASK_UNAVAILABLE`, and the UI disables the ask box (spec §6, criterion 12). The feature is therefore safe to merge and deploy ahead of any key existing.

**Reference material**: `refs/l4j-examples/google-ai-gemini-examples/` — Example05 (JSON response schema) is the pattern for the interpreter call; Example01 for builder/env-var conventions.

## Design

### New package: `com.solovis.entitlement.service.ask`

```
ask/
├── AskService.java              orchestrates: interpret → verify → explain → respond
├── QuestionInterpreter.java     interface: Proposal interpret(String question, CapabilityCatalog catalog)
├── Proposal.java                record: accountMention (String|null), capabilityKeys (List<String>),
│                                capabilityMention (String|null)
├── GeminiQuestionInterpreter.java  LangChain4j call, JSON response schema, prompt = catalogue + question
├── CapabilityCatalog.java       active capability keys + display names, grouped by area,
│                                built from the current snapshot
├── AccountMatcher.java          local mention → account resolution (see below)
└── AskController.java           POST /admin/v1/check/ask
```

**The confinement is structural** (spec §4, criterion 9): `GeminiQuestionInterpreter`'s constructor takes the `ChatModel` and nothing else; its one method takes the question and the catalogue. It has no reference to any repository, snapshot, or resolver — it cannot transmit what it cannot reach. `AskService` is the only class that touches both sides, and the data flows one way through it.

### Interpretation call

One request per question, structured JSON output (`ResponseFormat.JSON` + schema, per Example05):

- **System prompt**: the task ("extract which account and which capability this question asks about; account as the exact words used; capability as the best-matching keys from the catalogue, best first, or none"), followed by the catalogue rendered as grouped keys with display names (~500 keys ≈ a few KB — fits trivially).
- **Response schema**: `{ accountMention: string|null, capabilityKeys: string[], capabilityMention: string|null }`. `capabilityKeys` carries 0–3 plausible keys so capability ambiguity can surface as a candidate list (criterion 6), and `capabilityMention` is kept so an empty list can still say *what* wasn't matched ("Nothing in the registry matches 'parquet exports'").
- **Validation, locally, after the call**: keys not present in the active registry are dropped (criterion 10). One surviving key ⇒ proceed; several ⇒ `CLARIFY` with those candidates; none ⇒ **before declaring no-match, the mention (and any dropped key) is matched case-insensitively against *retired* capability keys and names** — a hit yields the "retired" answer (criterion 7). The catalogue sent contains only active capabilities, so retirement is always detected locally, never by the model.
- A null `accountMention` or an empty question part is the spec's "missing part" case: `NO_MATCH` naming what's absent ("Tell me which account you mean"), no check run.

### Account matching — local, deterministic

`AccountMatcher` resolves `accountMention` against the account store (name and external id), never via the model:

1. Exact match on external id, then case-insensitive exact on name → **one match**
2. Case-insensitive prefix / contains on name (indexed `lower(name)` column; 100k rows, single `LIKE` query) →
   - one hit → **one match**
   - 2–8 hits → **candidates** (criterion 6)
   - more → treated as no-match with a "be more specific" message
3. Nothing → **no match**, mention echoed back (criterion 5)

Thresholds live in one place and are unit-tested; tuning them never touches the interpreter.

### API contract

`POST /admin/v1/check/ask` — Role (future): Viewer, same as the classic checker. Conventions, value encoding and error model follow [`../001-entitlement-service/contracts/README.md`](../001-entitlement-service/contracts/README.md).

```jsonc
// request — question capped at 500 characters (validation error above), bounding
// both the per-call cost and what a runaway paste can send out
{ "question": "Can Acme export parquet?" }

// 200 — answered
{
  "status": "ANSWERED",
  "interpretation": { "account": { "external": "acme", "name": "Acme Corp" },
                      "capability": "export.parquet" },
  "result": { /* exactly the GET /admin/v1/check payload — same DTO, same code path (criterion 1, 3) */ }
}

// 200 — needs a pick
{
  "status": "CLARIFY",
  "interpretation": { "accountMention": "Acme", "capability": "export.parquet" },
  "accountCandidates": [ { "external": "acme", "name": "Acme Corp" },
                         { "external": "acme-emea", "name": "Acme EMEA Ltd" } ]
}                        // capabilityCandidates analogous when the capability is ambiguous;
                         // when BOTH are ambiguous, both lists are returned — the operator's
                         // picks fill the classic pickers and the classic check runs

// 200 — could not match
{ "status": "NO_MATCH",
  "unmatched": { "accountMention": "Acme Ltd" },        // and/or capabilityMention
  "detail": "No account matching 'Acme Ltd'" }

// 200 — retired
{ "status": "RETIRED_CAPABILITY", "capability": "export.csv" }

// 503 — feature off or Gemini unreachable/timed out
{ "error": "ASK_UNAVAILABLE" }
```

`ANSWERED.result` is produced by the same service method that backs `GET /admin/v1/check`; the ask path adds interpretation *around* the checker, never a second implementation of it (v1 spec criterion 24 preserved). A `CLARIFY` pick does not re-ask: the UI drops the chosen pair into the classic checker and runs it.

### UI — checker screen only

`AskBox` component above the existing pickers on the checker route:

- **Answered** → renders the existing `TraceView` plus an "Understood as: *Acme Corp* × *export.parquet*" chip; the pickers are also set to the interpreted pair, so the operator can tweak and re-run classically
- **Clarify** → candidate buttons; clicking fills the pickers and runs the classic check
- **No match / retired** → the plain statement, visually distinct from a not-allowed decision (criterion 8)
- **Unavailable** (503 or feature discovery) → box disabled with "Ask is unavailable — use the pickers below"

Feature discovery: `GET /admin/v1/meta` gains `"askEnabled": true|false`.

## Testing

- **Unit** — `AskService` against a stubbed `QuestionInterpreter`: every status, the invalid-key and retired-key validations, the one-way flow (interpreter mock asserts it was called with question + catalogue and nothing else — criterion 9's code-level half). `AccountMatcher`: exact/prefix/candidates/overflow/none.
- **Integration (MockMvc)** — `/check/ask` with the stub bean: answered payload equals `/check` payload for the same pair (criterion 1); 503 when no interpreter bean (criterion 12); no audit rows written by any ask call (criterion 11).
- **Live smoke** — `@EnabledIfEnvironmentVariable(GOOGLE_AI_GEMINI_API_KEY)`: a handful of canonical questions against seeded data, asserting interpretation correctness and the 3-second bound informally; plus a logging `ChatModelListener` assertion that the outbound request body contains only the question and catalogue (criterion 9's wire-level half). Skipped cleanly when the key is absent, so CI without a key stays green.
- **UI** — Vitest for `AskBox` states; one Playwright flow: ask → answer with trace; ask box disabled when meta says off.
- **Criterion 14** (load results unchanged) — no k6 work needed: the ask endpoint shares no code or thread pool with the decision paths; the 001 load demonstration simply remains the evidence, run on a build containing this feature.

## Rollout and coordination

- **Sequencing**: 001 is being implemented now in three worktrees (backend, UI, DB). This feature touches only `entitlement-service` (new `ask` package, one dependency, config) and `entitlement-ui` (one component, one meta field) — it builds **after** the 001 worktrees merge, as its own small branch. Nothing here blocks or is blocked by the DB layer or the SDK.
- **Safe-off default**: no API key ⇒ feature invisible. Merging the code changes no behaviour anywhere.
- **No new deployable, no schema change, no migration.** The audit trail is untouched by design (spec §9).

## Structure (files added/changed)

```
entitlement-service/
├── pom.xml                                        + langchain4j-google-ai-gemini 1.18.0
├── src/main/java/.../service/ask/                 the seven files above
├── src/main/resources/application.yaml            + entitlement.ask.*
└── src/test/java/.../service/ask/                 unit + MockMvc + gated live smoke

entitlement-ui/
└── src/routes/checker/                            + AskBox.tsx (+ tests); meta hook gains askEnabled
```
