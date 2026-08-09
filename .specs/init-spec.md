# Entitlement Service — Business Specification (v1)

**Status:** Draft for review
**Date:** 2026-08-08
**Companion document:** [`future-spec.md`](./future-spec.md) — everything deliberately deferred out of v1

---

## 1. Purpose

Every customer is on a plan that determines what they may do and how much of it. On top of that, sales, support, legal and billing all create one-off exceptions for individual accounts. Today that logic is scattered across the product as ad-hoc checks, so nobody can answer two basic questions with confidence: *what is this customer allowed to do*, and *why*.

This service is the single answer to both. Every product surface asks it instead of carrying its own rules.

It answers, for any account and any capability:

- **Are they allowed?**
- **What is the value** — a switch, a quantity, or a tier?
- **How was that decided** — which plan, which exceptions, what won and what lost?

---

## 2. Scope

### In scope

- A central registry of every capability the product gates on
- Plan definitions that grant baseline values
- Per-account exceptions that raise or restrict individual capabilities
- Which plan each account is on
- An evaluation interface returning a decision with a full explanation
- An audit trail of every change
- An operator UI for viewing and editing all of the above, and for checking any account against any capability

### Out of scope

These are named explicitly because they are the things people will otherwise assume are included.

| Not in scope | Why |
|---|---|
| **Counting usage** | The service publishes the limit; the calling product counts against it. This service never knows how much has been consumed. |
| **Pricing, billing, invoicing** | An entitlement is not a price. "What they may do" and "what they pay" are separate concerns. |
| **Authentication and user permissions** | This is about what an *account* may do, not what a *person within it* may do. (The operator roles for this service's own UI are a separate concern, defined in §9.) |
| **Customer-facing display of entitlements** | Internal service and internal UI only in v1. |
| **Migrating existing product checks** | Rewiring today's scattered ad-hoc checks to ask this service is rollout work for each product team, tracked outside this document. v1 is done when the service and its UI stand ready to answer. |

Deferred capabilities — time-bounded exceptions, upgrade-path hints, relative grants, bulk exceptions and others — are specified in [`future-spec.md`](./future-spec.md), not omitted by accident.

---

## 3. Domain model

### 3.1 Capability

One named thing an account may be allowed to do. Nothing can be evaluated unless it has been declared here — that registry is what prevents scattered ad-hoc checks from creeping back in.

Each capability declares:

- **A name**, grouped by area (`integration.salesforce`, `export.parquet`, `reports.monthly`)
- **A value type** — switch, quantity, or tier — which is the same across every plan. A capability cannot be a switch on one plan and a quantity on another.
- **A default value**, used when a plan does not mention it
- **An off-value**, optional, described in §5

Capabilities are **retired, never deleted**. Deleting one would silently rewrite history and orphan the exceptions that referenced it. A retired capability stops being evaluable but remains legible in the audit trail.

### 3.2 Plan

A named set of capability values forming the baseline for every account on it — for example free, pro, enterprise.

Plans are **partial**: a plan lists only the capabilities it sets. Anything unmentioned falls back to that capability's declared default, and the default appears as a visible step in the explanation so nothing resolves silently. This means adding a new capability does not require editing every plan before it can be used.

Plans are **flat** — there is no inheritance. "Enterprise includes everything in Pro" is not expressible; enterprise restates the values it wants. Inheritance makes explanations recursive, and clarity is the point of this service.

A plan with accounts on it cannot be deleted.

**Editing a plan changes every account on it**, the moment the change is live. That is what the affected-account count and the single-account preview exist for. Customers whose contracts promise a specific value are protected by a GRANT at that value, created before the plan moves. When existing customers must keep old terms wholesale, that is a deliberate act: the old plan is left untouched, a successor plan carries the new terms, and new accounts are pointed at it. Nothing keeps old terms by accident.

### 3.3 Account

The billable customer. An account belongs to **exactly one plan at a time**.

There is no hierarchy in v1 — no parent/child organisations, no resellers, no per-workspace entitlements.

This service **owns the plan assignment**. Billing tells it when a subscription changes; the UI can also set it. Every assignment change records where it came from, so drift between this service and billing is visible rather than silent.

New accounts are assigned a **designated default plan** at creation, so an account never exists in a state with no entitlements at all. Which plan is the default is chosen by an administrator, and changing that choice is recorded like any other change.

### 3.4 Overrides — GRANT and HOLD

An override is an exception attached to one account and one capability. There are two kinds, and the distinction is what makes the model work:

- **GRANT** — gives the account more than its plan does. Sales concessions, support goodwill, pilot access.
- **HOLD** — restricts the account below what it would otherwise have. Compliance suspensions, trust-and-safety actions, non-payment restrictions, and contractually negotiated reductions.

Overrides are **open-ended** — they last until someone removes them. They are **absolute values**, not adjustments: a GRANT says "200 reports", never "50 more than your plan".

An account may hold any number of GRANTs and HOLDs on the same capability. They do not conflict, because of how they combine (§4).

Overrides **persist untouched when an account changes plan**. Losing them silently would break promises the business has made. The consequence — a stale HOLD created under an old plan can keep suppressing a capability the customer has since paid for — is a known and accepted v1 limitation; see [`future-spec.md`](./future-spec.md) §2.

**Every override requires a reason.** An exception with no stated reason becomes unremovable in practice, because nobody later dares to touch it.

---

## 4. How a decision is reached

The effective value for an account and capability is:

1. Start with the **plan's value**, or the capability's default if the plan does not mention it.
2. Take the **most generous GRANT**, if any. If the plan is more generous than every GRANT, the plan wins — so a customer who upgrades to a better plan is never held back by an old, smaller concession.
3. Apply the **most restrictive HOLD**, if any. A HOLD can only reduce.

Stated as one line: **the effective value is the most generous of the plan and its GRANTs, then capped by the strictest HOLD.**

### Why this shape

The rule does not depend on order. It does not matter which override was created first, or in what sequence they are considered: the result depends only on what exists at the moment of the decision, never on history. That has three consequences worth stating:

- Two independent authors cannot clobber each other. A sales GRANT can never undo a compliance HOLD, and a compliance HOLD does not destroy the sales GRANT underneath it — lift the hold, and the concession is intact.
- The largest promise wins among GRANTs — a new, bigger concession takes effect immediately, with no cleanup of older, smaller ones — which is what the business actually intends.
- The explanation is never more than four lines: default or plan, winning GRANT, winning HOLD, result.

### Ordering requirement

"Most generous" and "most restrictive" require every value type to have a clear order from least to most generous:

- **Switch** — off is less generous than on
- **Quantity** — by the number, with `unlimited` as the highest possible value
- **Tier** — by the order the capability declares

Choices with no natural ordering — data residency regions, export formats, integrations, identity providers, notification channels, compliance attestations — do **not** get a special kind of value. Every one of them is in truth a *set* ("which of these may you use"), and a set is expressed as one switch per member: `residency.eu`, `export.parquet`, `integration.salesforce`. These resolve under the same rules with no special cases.

Where a plan grants "5 of 50 connectors", that is two capabilities: a quantity for how many may be active, and a switch per connector for which are permitted.

---

## 5. What "allowed" means

`allowed` answers exactly one question: **does this account hold this capability?** It does not answer "how much", and it does not answer "why".

A capability may declare an **off-value** — the value meaning not-available:

| Type | Off-value |
|---|---|
| **Switch** | `false`, inherently and always |
| **Quantity** | none by default; `0` may be declared as the off-value where zero genuinely means not-available |
| **Tier** | none by default; declared only where a level genuinely means absence (an SLA of `none`, as opposed to a support tier of `community`, which is a real benefit) |

`allowed` is true whenever the effective value differs from the off-value.

For quantities this means the answer is normally always `allowed: true`, and **the number is the answer**. A limit of `0` is a legitimate result meaning none-available-right-now, and the calling product handles it — consistent with §2, where counting and enforcing against a limit is explicitly the caller's job. A service that returns "limit 50, you count to 50" must also return "limit 0, you handle it", or it is applying two contradictory rules.

Callers must not infer *why* from the flag. Use `allowed` to decide access; for quantities, compare your own usage against the value; read the trace for anything else.

### Worked examples

| Capability | Plan | GRANT | HOLD | Effective | allowed |
|---|---|---|---|---|---|
| `api.access` (switch) | false | — | — | false | false |
| `api.access` | false | true | — | true | true |
| `api.access` | true | — | false | false | false |
| `reports.monthly` (quantity) | *unmentioned* | — | — | 0 (default) | true |
| `reports.monthly` | 50 | — | — | 50 | true |
| `reports.monthly` | 50 | 200 | — | 200 | true |
| `reports.monthly` | 50 | 200 | 0 | 0 | true — suspended; the trace says so |
| `reports.monthly` | 150 | 100 | — | 150 | true — plan beats a smaller grant |
| `seats` | unlimited | — | 100 | 100 | true |
| `support` (tier, no off-value) | community | — | — | community | true |
| `sla` (tier, off-value `none`) | none | — | — | none | false |

---

## 6. The evaluation interface

### 6.1 Single capability

Given an account and a capability, returns:

- **allowed** — as defined in §5
- **value** — the effective value
- **trace** — the full explanation

The **trace** names, in order: the capability default or the plan value that formed the baseline; every GRANT on that capability, marking which one won and why the others lost; every HOLD, marking which one won; and the resulting value. Each entry names its source, so a value of `0` arising from a capability default is distinguishable from an explicit plan value of `0`, and a suspension is distinguishable from an exhausted allowance.

The trace explains **denials as fully as grants**, including denial by absence.

The trace is a single artefact: the UI renders it directly, and products read the same one. There is no second, separately-maintained human explanation that can drift from it.

### 6.2 Whole account

Returns the decision for every capability that is not retired, for one account, in a single request. Page loads need dozens of answers, and asking dozens of separate questions would defeat the speed targets in §7.

Whole-account responses carry **values and allowed flags, but not traces** — returning hundreds of full traces on every page load contradicts §7. Traces come from single-capability requests and from the UI checker.

Every answer in a whole-account response must match what the single-capability request would have returned.

### 6.3 Errors

An unknown account, an unknown capability, or a retired capability is a **clear error**, never a silent denial. "We don't know" and "no" are different answers and must never be confused.

---

## 7. Speed, freshness and consistency

| Property | Target |
|---|---|
| Accounts | 100,000 |
| Decision volume | 5,000 single-capability decisions per second, sustained |
| Decision speed | 99 of every 100 single-capability decisions answered within 10 milliseconds |
| Whole-account speed | 99 of every 100 whole-account requests answered within 50 milliseconds |
| Change visibility | a saved change is reflected in decisions within **60 seconds**, end to end |
| Answer reuse by callers | calling products may reuse an answer for no longer than **10 seconds** |

Additional guarantees:

- **Targets hold while plans and overrides are being changed**, not only at rest. A system that is fast only when nothing is happening has not met this requirement.
- **One coherent moment per decision.** A single evaluation reflects the state as of one moment. It can never mix a new plan with the old plan's overrides, or one capability's new value with another's stale one.
- **Operators see their own changes at once.** An operator who saves a change and immediately re-checks in the UI sees their own change. This guarantee is scoped to the UI; everywhere else gets the 60-second bound.
- **Answers may be reused only briefly.** Calling products must not reuse an answer for longer than 10 seconds. The 60-second guarantee is an end-to-end promise, so it holds only if callers are bound too — a product reusing hour-old answers makes it fiction.
- **The promise is stated where changes are made.** Wherever an operator saves a change, the UI says when it will be live everywhere — "active within 60 seconds" — so a working-as-designed delay is never mistaken for a fault, and never becomes a support ticket.

---

## 8. Change management

Every change to a capability, a plan, an account's plan assignment, the default-plan designation, or an override is recorded with:

- **who** made it — a person, or the upstream system that did
- **when**
- **what** — before and after values
- **why** — mandatory free text on overrides

The history **only ever grows** — it cannot be edited or tidied — and is filterable by account, by plan, and by actor. It is retained for **24 months minimum**, which covers a full contract cycle plus renewal, so the reason behind any live exception can always be recovered.

**Plan edits state their reach.** Before saving, the plan editor shows how many accounts are affected and lets the operator preview the effect on one named account first. Editing an override touches one customer; editing a plan can touch thousands in a single click, and the UI must not treat those as the same act.

**No approval queue.** Speed is the entire reason exceptions exist. The audit trail and the affected-account warning carry the safety.

**No plan rollback.** Removing an override is deleting it. Reverting a plan edit is a genuinely different operation — accounts move between plans in the meantime, so replaying an old plan version can grant capabilities to accounts that were never meant to have them. Pretending these are the same feature is a trap; see [`future-spec.md`](./future-spec.md) §6.

**HOLDs can be removed by anyone with override rights**, with the removal audited. This is a known v1 gap: it means a compliance suspension can be lifted by someone who should not be lifting it. Accepted for v1; see [`future-spec.md`](./future-spec.md) §4.

---

## 9. The UI

### Screens

1. **Capability registry** — declare capabilities, their type, default, off-value and grouping; retire capabilities.
2. **Plans** — list all plans with how many accounts are on each; edit a plan's capability values, with the affected-account count shown and a single-account preview before saving; designate the default plan for new accounts.
3. **Account view** — the account's plan, its effective entitlements with each value marked as coming from a default, a plan, a GRANT or a HOLD, and its list of overrides with add and remove.
4. **Checker** — pick an account and a capability, see the decision and its explanation rendered as a readable chain.
5. **Change history** — filterable by account, plan and actor, showing before and after.

### Roles

| Role | Can do |
|---|---|
| **Administrator** | Everything, including capabilities and plans |
| **Exception manager** | Create and remove overrides, use the checker, read plans but not edit them. The sales- and support-facing role. |
| **Viewer** | Read everything, change nothing. The support-facing read-only role. |

### Capability grouping

Because sets are modelled as one switch per member, a twelve-region residency set is twelve capabilities. The registry and the plan editor must **group capabilities by area** (`integration.*`, `export.*`, `residency.*`) with collapse and search, or the plan editor degenerates into an unusable wall of toggles.

---

## 10. Acceptance criteria

The spec is satisfied when every criterion below can be demonstrated.

### Model

1. A capability cannot be evaluated unless declared, and has one value type across all plans.
2. Quantities express *unlimited* as a distinct value, never as a large number.
3. Tiers declare an order, and that order is visible to callers, so "at least tier X" is answerable.
4. Plans are partial; an unmentioned capability resolves to its declared default, and the default is visible in the trace.
5. Plans do not inherit from one another.
6. An account has exactly one plan; a plan with accounts on it cannot be deleted.
7. A new account is assigned the default plan and is never without entitlements.
8. Capabilities can be retired but not deleted; retired capabilities remain legible in history.
9. Overrides cannot be saved without a reason.

### Decisions

10. With no overrides, the plan value is returned; with no plan value, the capability default is returned.
11. A GRANT more generous than the plan wins; a GRANT less generous than the plan does not lower it.
12. The most generous GRANT wins among several, regardless of creation order.
13. The most restrictive HOLD is applied after GRANTs, regardless of creation order.
14. A HOLD suppresses a capability even when a GRANT exists, and removing the HOLD restores the GRANT's value with no further action.
15. Removing a GRANT restores the plan value with no further action.
16. Evaluating the same state twice in any order produces the same result.
17. A GRANT may grant a capability the plan does not mention at all.
18. `allowed` reflects only whether the account holds the capability, per §5, and never depends on how the value was reached.
19. Unknown account, unknown capability and retired capability each return a clear error, never a silent denial.
20. Every capability that is not retired can be fetched for an account in one request, and each answer matches the single-capability request.

### Explanations

21. Every single-capability decision carries a trace naming the baseline, every GRANT, every HOLD, the winner in each group, and the result.
22. Every trace entry names its source, so a defaulted `0`, an explicit plan `0`, and a HOLD-suppressed `0` are distinguishable.
23. Traces explain denials as fully as grants, including denial by absence.
24. The trace the UI renders is the very trace the evaluation returned, never a separately maintained copy.

### Speed, freshness and consistency

25. With 100,000 accounts and 5,000 single-capability decisions per second sustained, 99 of every 100 decisions are answered within 10 milliseconds.
26. Whole-account requests are measured separately: 99 of every 100 are answered within 50 milliseconds.
27. Targets 25 and 26 hold while plans and overrides are being changed, not only at rest.
28. A saved change is reflected in decisions within 60 seconds, end to end.
29. The 10-second bound on reusing answers is documented as a condition of criterion 28, and callers are held to it.
30. An operator re-checking in the UI immediately after saving sees their own change.
31. One evaluation reflects one coherent moment and never mixes new and stale state.

### Change safety

32. Every change to a capability, plan, assignment, default-plan designation or override records actor, timestamp, before/after and source.
33. History can be added to but never edited or removed, is filterable by account, plan and actor, and is retained for at least 24 months.
34. The plan editor states the affected account count before saving.
35. The plan editor previews the effect of a pending change on a named account before saving.
36. A plan assignment change records whether it came from a person or an upstream system.

### UI

37. Each of the three roles can complete its work and cannot exceed its permissions.
38. The checker returns a decision and rendered explanation for any account and capability.
39. An account's effective entitlements are viewable in one place, each marked as coming from a default, plan, GRANT or HOLD.
40. Capabilities are grouped by area in the registry and plan editor, with collapse and search.
41. Wherever a change is saved, the UI states when it will be live everywhere.

### Definition of done

Every criterion above is demonstrable through the UI or the evaluation interface by someone with no access to internals, and criteria 25–31 are evidenced by a demonstration at the stated volumes, run **against data that is changing during the demonstration**.

---

## 11. Deliberately left to the technical implementation plan

These are real decisions, excluded from this document because they are engineering choices rather than business rules:

- **How calling products stay safe when the service cannot answer.** The business posture is fixed: an outage must neither take away what a customer had nor grant what they lacked — products carry on with the last answer they saw until the service answers again. The mechanism that honours this posture is engineering's to choose.
- **How a plan's past values are recorded.** The business rule is settled in §3.2 — a plan edit applies to everyone on the plan. Whether that history is kept by amending one record or by keeping distinguishable versions of it is pure record-keeping, and engineering's to choose.

---

## 12. Known v1 limitations

Accepted knowingly, each with its resolution in [`future-spec.md`](./future-spec.md):

| Limitation | Consequence |
|---|---|
| Overrides never expire | Every temporary promise must be removed by hand |
| A stale HOLD survives a plan upgrade | A customer can pay for a capability that an old suspension still blocks |
| Anyone can remove a HOLD | A compliance suspension can be lifted by someone who should not be able to |
| Grants are absolute, not relative | "20 seats more than their plan" cannot be expressed, and evaporates on a plan upgrade |
| Denials carry no upgrade path | Products must map "what would grant this" themselves |
| No bulk or segment overrides | An exception for forty acquired accounts is forty manual records |
| Commercial reductions are HOLDs | "Contractually agreed lower limit" and "suspended for fraud" share one record type |
