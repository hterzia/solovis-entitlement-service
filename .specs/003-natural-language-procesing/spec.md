# Plain-English Checker — Business Specification

**Status:** In implementation — the service side is built and tested (§13); the checker connection, the ask box, and date understanding (§3, criteria 15–21) are the remaining work
**Date:** 2026-08-09, date understanding added 2026-08-10
**Companion documents:** [`001-entitlement-service/spec.md`](../001-entitlement-service/spec.md) — the entitlement service this extends · [`002-time-bound-override/spec.md`](../002-time-bound-override/spec.md) — the point-in-time checker this asks questions of · [`plan.md`](./plan.md) — how it will be built

---

## 1. Purpose

The checker answers *is this account allowed, what is the value, and why* — but it asks the operator to already know two exact things: which account record and which capability key. Questions do not arrive at support and sales desks in that form. They arrive as sentences: *"Can Acme export parquet?"*, *"How many monthly reports does Globex get?"*, *"Does Initech have API access?"*.

They also arrive about the past. *"How many reports could Acme export last month?"* is the shape a billing dispute takes, and the checker can now answer it — given a date. The date, too, arrives inside the sentence rather than in a field.

This feature lets an operator type the question the way it arrived. The system works out which account, which capability, and — when the question names one — which date is being asked about, runs the same check the checker has always run, and shows the same decision with the same explanation.

One principle governs everything below: **the feature understands questions; it never answers them.** Every answer shown comes from the decision engine, exactly as if the operator had filled in the checker by hand.

---

## 2. Scope

### In scope

- An ask box on the operator UI's checker screen, alongside the existing account and capability pickers
- Interpretation of a typed question into one account, one capability, and at most one date
- The interpretation shown with every answer — account, capability **and the date understood** — so the operator sees what was understood
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
| **A range of dates in one question** | *"…over the last quarter"* is a report, not a question. One question resolves to at most one date, mirroring the point-in-time feature's own bound of one account, one capability, one date. |

---

## 3. How a question becomes an answer

1. The operator types a question.
2. The interpreter proposes what the question mentions: an account, by whatever name the operator used; one capability from the registry; and, if the question names a moment in time, the date it means.
3. The account mention is matched against the real account records; the capability is confirmed to exist in the registry. This happens inside the service — the interpreter proposes, the service verifies. **An account or capability the interpreter names must actually exist, or the question is unmatched; the interpreter cannot conjure records.**
4. The existing check runs for that account and capability — as at the date, if the question named one, and about now if it did not. Both are the checker's own routes, unchanged.
5. The answer is shown exactly as the classic checker shows it — decision, value, full explanation — together with the interpretation: *"Understood as: Acme Corp × export.parquet"*, and *"as at 15 July 2026"* whenever a date was understood.

### Never a silent guess

- **One confident match** → the answer, with the interpretation displayed.
- **Several plausible matches** → no answer yet. The candidates are listed, the operator picks one, and the pick runs the classic check. Choosing is the operator's act, not the system's.
- **No match** → a plain statement of which part failed: *"No account matching 'Acme Ltd'"*, *"Nothing in the registry matches 'parquet exports'"*. Saying *we did not understand* and saying *no* are different answers and are never confused — the same rule the entitlement service applies to unknown records (the v1 spec §6.3).
- **A missing part** → the same plain statement. A question that names no account at all — *"Can they export parquet?"* — is answered by asking which account is meant; a question that names no capability, likewise. Absent and unmatched are both *we did not understand*, never a guess.
- **A date that cannot be pinned to one day** — *"a while back"*, *"recently"* — is unmatched like any other part, and says so. A vague date is never rounded into a specific one.
- **A retired capability, asked about by name** → see below; retirement is a fact worth stating, and what it means depends on whether the question is about now or about the past.

### The date is understood, never assumed

A question that names no date is about now, and behaves exactly as it always has. **The absence of a date is never treated as a date.**

A question that does name one is answered about that date, using the checker's own point-in-time route — so every rule that route already applies applies here unchanged, and this feature adds no rules of its own about the past:

- **A date in the future** is refused, in the point-in-time feature's own words.
- **A date before the account existed**, and **a date reaching further back than the records go**, are each stated as such — never as a denial, and never as today's value wearing a past date.
- **A capability retired since that date** is answered normally, with its retirement stated alongside. Asking about a retired capability *without* a date remains what it has always been: a plain statement that the capability is retired, and no check.

**The date understood is always displayed**, in words, beside the account and the capability. Working out that *"last month"* means a particular day is the one part of interpretation with no local record to check it against, so the safeguard is that the operator always sees the day the system settled on, and can correct it by using the checker's own date field.

Yes/no questions and how-much questions are the same act. *"Can Acme export parquet?"* and *"How many reports does Acme get?"* both show the full result — allowed, value, explanation — because the value and the reason are the answer to both.

---

## 4. The interpreter's confines

The interpreter runs **before** anything is looked up, and the flow is one-way: question out, interpretation back, then the local decision runs. Structurally, the interpreter is given exactly three things:

1. **The question text**, verbatim
2. **The capability catalogue** — the names and groupings of capabilities, so "parquet export" can be matched to `export.parquet`. Names only: not defaults, not which plans set them, not any account's value. Retired capabilities are included by name, because a question about a past date may be about a capability retired since — but **whether a capability is retired, and what that means, is always decided locally**, never by the interpreter.
3. **Today's date** — the one fact required to turn *"last month"* into a particular day. Nothing else about the state of the service accompanies it.

It is never given — and so can never see — entitlement values, decisions, explanations, plan contents, plan assignments, the existence of any override, any reason text, any history, or the account roster. The account list never leaves the service: the interpreter only extracts the account mention *from the question the operator typed*, and matching that mention to a real account happens locally. The interpreter also never sees the outcome of the question it interpreted.

The third item is the only addition this feature makes to what leaves the building, and it is deliberately the least revealing fact the service holds: today's date is knowable by anyone, and discloses nothing about any customer.

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

Interpretation is performed by an outside language service. Per question, three things are sent: the question text as typed, the capability catalogue (names and groupings, retired ones included), and today's date. Nothing else — no values, no decisions, no explanations, no reasons, no account roster, no history.

This list is exhaustive and is meant to be checked against what the service actually transmits, not merely asserted (criterion 9).

One residual is accepted rather than hidden: the question is free text, and an operator who types sensitive context into it — *"can Acme still export? legal froze them last week"* — has sent that context out. This is an internal tool used by trusted operators; the residual is accepted for this feature the way the v1 spec §12 records its own accepted conditions. The commercial terms under which the outside service handles submitted text — including that it is not retained for the provider's own use — are an implementation requirement, verified in the technical plan.

---

## 8. Speed

| Property | Target |
|---|---|
| An interpreted answer about now, end to end | 95 of every 100 within 3 seconds while the language service is healthy |
| An interpreted answer about a past date, end to end | 95 of every 100 within 5 seconds — interpretation added to the point-in-time feature's own 3-second target for a past answer |
| The classic checker, and every v1 speed target | Completely untouched — asking adds no work to any decision path |

Asking is an operator convenience measured in seconds, not a decision path measured in milliseconds. The two are never allowed to share a fate.

---

## 9. Change management

Asking is not a change. Questions do not appear in the change history — history records changes, and asking changes nothing. Whether questions are kept in operational logs for debugging is engineering's concern, not part of the audited record.

---

## 10. Acceptance criteria

The feature is satisfied when every criterion below can be demonstrated.

### Answers

1. A question naming a real account and a real capability returns exactly the decision and explanation the classic checker returns for that same pair.
2. Every answer displays the interpretation — which account and which capability were understood, and the date, whenever the question named one.
3. The explanation shown is the decision engine's own trace; no generated or separately maintained explanation exists anywhere in the feature.
4. Yes/no and how-much phrasings of the same question produce the same full result.

### Never a silent guess

5. A question whose account mention matches no account — or that names no account at all — states which part is missing or unmatched, and runs no check. The same holds for the capability.
6. A question that plausibly matches several accounts or capabilities lists the candidates and answers only after the operator picks.
7. A question about a retired capability, naming no date, is answered with the fact of its retirement, and no check runs.
8. An unmatched question is visibly different from a not-allowed answer — *we did not understand* is never rendered as *no*.

### Confinement

9. The interpreter receives only the question text, the capability catalogue and today's date; the account roster is never transmitted; the interpreter never receives the result of any check. Demonstrable by inspecting what the service actually sends.
10. An account or capability proposed by the interpreter that does not exist in the service's own records is treated as unmatched, never answered.
11. Asking performs no writes and produces no change-history entries.

### Availability and speed

12. With the language service unconfigured or unreachable, the ask box states it is unavailable and the classic checker is fully functional.
13. 95 of every 100 interpreted answers about now complete within 3 seconds while the language service is healthy.
14. The v1 demonstration results are the same with this feature deployed as without it.

### Dates

15. A question naming a date returns exactly what the classic checker returns for that account, that capability and that date.
16. A question naming no date is answered about now. The absence of a date is never treated as a date, and never silently becomes today.
17. Every answer that used a date displays the date understood, in words, alongside the account and capability.
18. A date that cannot be pinned to a single day — *"recently"*, *"a while back"* — is stated as unmatched and no check runs. A vague date is never rounded into a specific one.
19. A date in the future, a date before the account existed, and a date reaching further back than the records go are each stated in the point-in-time feature's own words — never as a denial, and never as today's value presented as the past.
20. A capability retired since the date asked about is answered normally with its retirement stated; the same capability asked about with no date is answered by criterion 7 instead.
21. 95 of every 100 interpreted answers about a past date complete within 5 seconds while the language service is healthy.

---

## 11. Deliberately left to the technical implementation plan

- **Which language service and model interpret the question**, and under what commercial terms
- **How the question and catalogue are presented** to the language service, and how its proposal is validated
- **How account mentions are matched** to records, and where the line sits between one confident match and a candidate list
- **How a date mention becomes a date** — where the line sits between a phrase specific enough to answer and one too vague to pin down
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
| A date understood from words has no local record to check it against | Unlike an account or a capability, *"last month"* cannot be verified against anything the service holds. The mitigation is that the date settled on is always displayed (§3), so a misreading is visible rather than silent — but an operator who does not read it can act on the wrong day |
| Dates are read against the service's own clock | *"Yesterday"* means yesterday in US Eastern, the one clock the service uses everywhere. An operator in another zone asking late in the evening may mean a different day than the one understood |
| One date per question, and no ranges | A dispute spanning a quarter is asked one day at a time |
| Phrasing is English-first | Other languages may work but are not promised |

---

## 13. Implementation status — 2026-08-10

The service side of this feature is built and its behaviour demonstrated by tests, including live questions answered by the outside language service.

**What changed since 9 August.** Two connections were open then, each waiting on entitlement service work in progress under 001: the link to the classic checker itself, and the ask box on the checker screen. **Both of those now exist.** The checker answers, and the checker screen is built. Neither connection is blocked any longer; they are simply the remaining work, and they are the last thing standing between this feature and every one of its criteria.

**Credentials for the outside language service are now provisioned**, so the feature can be switched on and demonstrated rather than only reasoned about. It remains off wherever those credentials are absent, which is the posture §6 requires and what lets the code merge at any time without changing behaviour anywhere.

**Dates are newly specified and entirely unbuilt.** Everything in §3's *"The date is understood, never assumed"*, and criteria 15–21, was added on 10 August once the point-in-time checker became imminent. This is the substantial remaining work. The point-in-time route it calls is now **finished and shipped** under the time-bounded overrides feature, so nothing blocks it.

| What | State |
|---|---|
| Interpretation of real questions by the outside language service | **Working and demonstrated live** — a canonical question resolves to the right account mention and capability; a question about a capability the registry does not know comes back empty rather than guessed |
| Never a silent guess — candidates, missing parts, unmatched parts, retirement stated as a fact (criteria 5–8, 10) | **Built and demonstrated by tests** covering every outcome |
| The interpreter's confines (§4, criterion 9) | **Built structurally** — the interpreter is given only what §4 lists and can reach nothing else. The complementary demonstration, showing what actually leaves the building on a real question, is not yet written |
| Asking changes nothing (criterion 11) | **Built** — the ask path touches nothing that records changes. True by construction; not yet demonstrated by a test that watches the change history across a question |
| Graceful absence (§6, criterion 12) | **Service half built and demonstrated** — unconfigured or unreachable answers a plain "unavailable". The ask box that must say so on screen is part of the remaining work |
| Answers are the classic checker's, with interpretation shown (criteria 1–4) | **Built up to the connection point.** The checker it connects to now exists; making the connection is remaining work, no longer a dependency |
| **Dates — understanding one, showing it, and answering about it (criteria 15–21)** | **Not built.** Newly specified 10 August; the largest remaining piece of this feature |
| Speed about now (criterion 13) | The interpretation step measures well inside the 3-second target; end to end awaits the connection |
| Speed about a past date (criterion 21) | Not yet measurable — awaits the date work; the point-in-time route itself is shipped |
| Load results unchanged (criterion 14) | Awaits the v1 volume demonstration, which should be run once on a build carrying this feature and the time-bounded overrides together, so it settles both features' equivalent criteria at once |
| Commercial terms for submitted text (§7) | **Verified** — the language service is used under paid terms that exclude retaining submitted text for the provider's own use. Re-confirm at each credential rotation |

The feature ships dark: without its configuration it is invisible, so its code can merge at any time without changing behaviour anywhere.
