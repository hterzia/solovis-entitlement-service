# Plain-English Checker — Business Specification

**Status:** In implementation — the service side is built and tested (§13); the checker connection and the ask box await the entitlement service's own checker
**Date:** 2026-08-09
**Companion documents:** [`001-entitlement-service/spec.md`](../001-entitlement-service/spec.md) — the entitlement service this extends · [`plan.md`](./plan.md) — how it will be built

---

## 1. Purpose

The checker answers *is this account allowed, what is the value, and why* — but it asks the operator to already know two exact things: which account record and which capability key. Questions do not arrive at support and sales desks in that form. They arrive as sentences: *"Can Acme export parquet?"*, *"How many monthly reports does Globex get?"*, *"Does Initech have API access?"*.

This feature lets an operator type the question the way it arrived. The system works out which account and which capability are being asked about, runs the same check the checker has always run, and shows the same decision with the same explanation.

One principle governs everything below: **the feature understands questions; it never answers them.** Every answer shown comes from the decision engine, exactly as if the operator had filled in the checker by hand.

---

## 2. Scope

### In scope

- An ask box on the operator UI's checker screen, alongside the existing account and capability pickers
- Interpretation of a typed question into one account and one capability
- The interpretation shown with every answer, so the operator sees what was understood
- A pick-list when the question plausibly matches several accounts or capabilities
- A plain statement of failure when part of the question cannot be matched
- Graceful absence: when interpretation is not available, the classic checker is untouched

### Out of scope

| Not in scope | Why |
|---|---|
| **Creating or removing overrides by typed instruction** | Write-path interpretation needs its own confirmation design. The checker is read-only and stays read-only. |
| **Asking questions of the change history** | A different feature over different data. |
| **Rewriting or summarising the explanation in prose** | The trace is the explanation. A second, generated explanation could drift from it, which the entitlement specification forbids (v1 spec criterion 24). |
| **Anything customer-facing** | Operator tool only, like everything else in the management UI. |
| **Conversations and follow-ups** | Every question stands alone. "What about Globex?" after a question about Acme is not understood. |
| **Several accounts or capabilities in one question** | One question resolves to one account and one capability. Compound questions are asked one at a time. |

---

## 3. How a question becomes an answer

1. The operator types a question.
2. The interpreter proposes what the question mentions: an account, by whatever name the operator used, and one capability from the registry.
3. The account mention is matched against the real account records; the capability is confirmed to exist in the registry. This happens inside the service — the interpreter proposes, the service verifies. **An account or capability the interpreter names must actually exist, or the question is unmatched; the interpreter cannot conjure records.**
4. The existing check runs for that account and capability.
5. The answer is shown exactly as the classic checker shows it — decision, value, full explanation — together with the interpretation: *"Understood as: Acme Corp × export.parquet"*.

### Never a silent guess

- **One confident match** → the answer, with the interpretation displayed.
- **Several plausible matches** → no answer yet. The candidates are listed, the operator picks one, and the pick runs the classic check. Choosing is the operator's act, not the system's.
- **No match** → a plain statement of which part failed: *"No account matching 'Acme Ltd'"*, *"Nothing in the registry matches 'parquet exports'"*. Saying *we did not understand* and saying *no* are different answers and are never confused — the same rule the entitlement service applies to unknown records (the v1 spec §6.3).
- **A missing part** → the same plain statement. A question that names no account at all — *"Can they export parquet?"* — is answered by asking which account is meant; a question that names no capability, likewise. Absent and unmatched are both *we did not understand*, never a guess.
- **A retired capability, asked about by name** → the answer says the capability is retired, not merely "no match". Retirement is a fact worth stating.

Yes/no questions and how-much questions are the same act. *"Can Acme export parquet?"* and *"How many reports does Acme get?"* both show the full result — allowed, value, explanation — because the value and the reason are the answer to both.

---

## 4. The interpreter's confines

The interpreter runs **before** anything is looked up, and the flow is one-way: question out, interpretation back, then the local decision runs. Structurally, the interpreter is given exactly two things:

1. **The question text**, verbatim
2. **The capability catalogue** — the names and groupings of active capabilities, so "parquet export" can be matched to `export.parquet`. Names only: not defaults, not which plans set them, not any account's value.

It is never given — and so can never see — entitlement values, decisions, explanations, plan contents, plan assignments, the existence of any override, any reason text, any history, or the account roster. The account list never leaves the service: the interpreter only extracts the account mention *from the question the operator typed*, and matching that mention to a real account happens locally. The interpreter also never sees the outcome of the question it interpreted.

The interpretation itself is a service the system consults — it is not part of the decision. Removing this feature entirely would change no decision, no value, and no explanation anywhere.

---

## 5. Where it lives

The management layer's operator UI, on the checker screen — nowhere else. Consuming products and their replicas are untouched: no product-facing interface gains a natural-language surface, and nothing about the replication feed or the client changes.

Whoever can use the checker can ask questions. v1 of the entitlement service ships without sign-in (recorded deviation, 001 plan), so this feature inherits that posture and adds no access rules of its own. When roles arrive, asking follows the checker's role — the Viewer level.

---

## 6. When the interpreter is unavailable

Interpretation depends on an outside language service. When that service is unreachable, unconfigured, or answering badly:

- The ask box says plainly that asking is unavailable right now
- The classic checker — pickers, decision, explanation — works exactly as before, because it never depends on the interpreter for anything
- No decision, anywhere in the estate, is affected

Convenience degrades; answers do not.

---

## 7. What leaves the building

Interpretation is performed by an outside language service. Per question, two things are sent: the question text as typed, and the capability catalogue (names and groupings). Nothing else — no values, no decisions, no explanations, no reasons, no account roster, no history.

One residual is accepted rather than hidden: the question is free text, and an operator who types sensitive context into it — *"can Acme still export? legal froze them last week"* — has sent that context out. This is an internal tool used by trusted operators; the residual is accepted for this feature the way the v1 spec §12 records its own accepted conditions. The commercial terms under which the outside service handles submitted text — including that it is not retained for the provider's own use — are an implementation requirement, verified in the technical plan.

---

## 8. Speed

| Property | Target |
|---|---|
| An interpreted answer, end to end | 95 of every 100 within 3 seconds while the language service is healthy |
| The classic checker and every the v1 spec §7 target | Completely untouched — asking adds no work to any decision path |

Asking is an operator convenience measured in seconds, not a decision path measured in milliseconds. The two are never allowed to share a fate.

---

## 9. Change management

Asking is not a change. Questions do not appear in the change history — history records changes, and asking changes nothing. Whether questions are kept in operational logs for debugging is engineering's concern, not part of the audited record.

---

## 10. Acceptance criteria

The feature is satisfied when every criterion below can be demonstrated.

### Answers

1. A question naming a real account and a real capability returns exactly the decision and explanation the classic checker returns for that same pair.
2. Every answer displays the interpretation — which account and which capability were understood.
3. The explanation shown is the decision engine's own trace; no generated or separately maintained explanation exists anywhere in the feature.
4. Yes/no and how-much phrasings of the same question produce the same full result.

### Never a silent guess

5. A question whose account mention matches no account — or that names no account at all — states which part is missing or unmatched, and runs no check. The same holds for the capability.
6. A question that plausibly matches several accounts or capabilities lists the candidates and answers only after the operator picks.
7. A question about a retired capability is answered with the fact of its retirement.
8. An unmatched question is visibly different from a not-allowed answer — *we did not understand* is never rendered as *no*.

### Confinement

9. The interpreter receives only the question text and the capability catalogue; the account roster is never transmitted; the interpreter never receives the result of any check. Demonstrable by inspecting what the service sends.
10. An account or capability proposed by the interpreter that does not exist in the service's own records is treated as unmatched, never answered.
11. Asking performs no writes and produces no change-history entries.

### Availability and speed

12. With the language service unconfigured or unreachable, the ask box states it is unavailable and the classic checker is fully functional.
13. 95 of every 100 interpreted answers complete within 3 seconds while the language service is healthy.
14. The the v1 spec §7 demonstration results are the same with this feature deployed as without it.

---

## 11. Deliberately left to the technical implementation plan

- **Which language service and model interpret the question**, and under what commercial terms
- **How the question and catalogue are presented** to the language service, and how its proposal is validated
- **How account mentions are matched** to records, and where the line sits between one confident match and a candidate list
- **How the confinement of §4 is enforced and demonstrated** in code and tests

---

## 12. Known limitations

Accepted knowingly:

| Limitation | Consequence |
|---|---|
| The question is free text sent to an outside service | Sensitive context an operator types into a question leaves with it (§7) |
| One account and one capability per question | Compound questions must be asked one at a time |
| No conversation | Each question stands alone; follow-ups are not understood |
| Interpretation is best-effort | A well-formed question may still fail to interpret; the classic checker is always available and always authoritative |
| Phrasing is English-first | Other languages may work but are not promised |

---

## 13. Implementation status — 2026-08-09

The service side of this feature is built and its behaviour demonstrated by tests, including live questions answered by the outside language service. Two connections remain open by design, because both depend on entitlement-service work still in progress under 001: the link to the classic checker itself, and the ask box on the checker screen. Until the checker link exists, asking answers "unavailable" — which is the posture §6 requires anyway.

| What | State |
|---|---|
| Interpretation of real questions by the outside language service | **Working and demonstrated live** — a canonical question resolves to the right account mention and capability; a question about a capability the registry does not know comes back empty rather than guessed |
| Never a silent guess — candidates, missing parts, unmatched parts, retirement stated as a fact (criteria 5–8, 10) | **Built and demonstrated by tests** covering every outcome |
| The interpreter's confines (§4, criterion 9) | **Built structurally** — the interpreter component is given the question and the catalogue and can reach nothing else |
| Asking changes nothing (criterion 11) | **Built** — the ask path touches nothing that records changes |
| Graceful absence (§6, criterion 12) | **Service half built and demonstrated** — unconfigured or unreachable answers a plain "unavailable"; the ask-box half waits on the checker screen |
| Answers are the classic checker's, with interpretation shown (criteria 1–4) | **Built up to the connection point** — waits for the checker itself, in progress under 001 |
| Speed (criterion 13) | The interpretation step measures well inside the 3-second target; end to end waits for the checker |
| Load results unchanged (criterion 14) | Waits for the 001 volume demonstration, run on a build containing this feature |
| Commercial terms for submitted text (§7) | **Verified** — the language service is used under paid terms that exclude retaining submitted text for the provider's own use |

The feature ships dark: without its configuration it is invisible, so its code can merge at any time without changing behaviour anywhere.
