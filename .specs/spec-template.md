# <Feature name> — Business Specification

**Status:** Draft for review
**Date:** <YYYY-MM-DD>
**Companion documents:** <link the specs this extends or defers to — e.g. [`001-entitlement-service/spec.md`](../001-entitlement-service/spec.md) — the specification this extends · [`future-spec.md`](../future-spec.md) — deferred scope, item N>

---

> **About this template.** It is the shape shared by the three specifications in `.specs/`, with the
> conventions that make them consistent. Every `>` blockquote is guidance and is deleted as you
> write. Sections marked **(extensions only)** are omitted by a first specification of a thing.
>
> **The one rule that governs the rest: this is a business document.** No technical vocabulary at
> all — no p99, latency, throughput, caching, append-only, read-your-writes, snapshot, blast radius,
> fail open/closed, load test. Say *99 of every 100 answers within 10 milliseconds*, *reuse an
> answer*, *only ever grows*. Where a decision looks technical but has a business kernel — what
> happens during an outage, who keeps old terms after a change — **state the posture here and defer
> only the mechanism** to `plan.md`. That boundary is what keeps it clear who owns which decision.

---

## 1. Purpose

> The problem in the language of the people who have it, then what this feature is. Name the
> question nobody can currently answer, or the promise nobody can currently express. Two or three
> paragraphs.
>
> If one principle governs everything below, state it here in bold and hold to it — *"the feature
> understands questions; it never answers them"* does more work than a page of rules.
>
> If this feature exists because an earlier document deferred it, say so and link the item.

---

## 2. Scope

### In scope

> A bulleted list of what is being built, in business terms. Each line something a reader could
> check for afterwards.

### Out of scope

These are named explicitly because they are the things people will otherwise assume are included.

| Not in scope | Why |
|---|---|
| **<Thing>** | <Why it isn't here. A reason, never "not needed" — either it belongs to another system, it is deferred with a pointer, or it was considered and rejected.> |

> Every exclusion carries a why. An unexplained exclusion reads as an oversight and gets re-proposed
> in the next review. Deferred items link to `future-spec.md`.

---

## 3. <The domain model, or what changes about it>

> **§3–§6 are the shaped middle.** Their titles belong to the feature; only their job is fixed —
> define the nouns, state the rule, pin the definitions the rule leans on, describe the interface.
> The three existing specs named them: *Domain model · How a decision is reached · What "allowed"
> means · The evaluation interface* (001); *What changes about an override · How a decision is
> reached · Records are never destroyed · Asking about the past* (002); *How a question becomes an
> answer · The interpreter's confines · Where it lives · When the interpreter is unavailable* (003).
> §1–§2 and §7 onwards keep their titles and order.

> A first specification defines its nouns here, one subsection each (§3.1 Capability, §3.2 Plan…):
> what it is, what it declares, what may and may not be done to it, and the rule that protects it —
> *"capabilities are retired, never deleted, because deleting one would silently rewrite history"*.
>
> A feature extending an existing spec instead describes only what changes, and says plainly what
> does not: *"give neither and the override behaves exactly as it does today"*.
>
> State the invariants as prose with their reasons attached. A rule whose reason is written down
> survives the next person; one without it gets "simplified" away.

---

## 4. How a decision is reached

> The rule itself, as numbered steps, then one sentence that states it whole.

### Why this shape

> The properties the rule buys, and what they mean for the business — who can no longer clobber
> whom, what no longer needs cleaning up, what becomes impossible to get wrong. If the rule is
> order-independent, or deterministic, or bounded in length, say so here: those are the claims the
> acceptance criteria will have to demonstrate.

### <What this preserves, and what it relaxes> *(extensions only)*

> When a feature weakens a property an earlier spec promised, name the property, say it is being
> relaxed, and say why the trade is right. Then record it in the amendments section. Quietly
> contradicting an earlier spec is how two documents both become untrustworthy.

---

## 5. <Definitions the rest of the document leans on>

> Whatever needs pinning down before the interface can be described: what *allowed* means, what is
> never destroyed, what an outside component is and is not given. Worked examples belong here — a
> table of inputs and their expected result is the clearest thing in a specification, and it doubles
> as a ready-made test fixture, so write it as one.

| <Input> | <Input> | <Result> | <allowed> |
|---|---|---|---|
| | | | |

---

## 6. The interface

> What can be asked, what comes back, and who may ask. Split by shape of request if there is more
> than one (single item, whole account, past date).

### Errors

> **"We don't know" and "no" are different answers, and must never be confused.** Unknown record,
> unknown capability, unavailable data — each is a clear error, never a silent denial. This appears
> in all three existing specs; it is a house rule, not a per-feature choice.

---

## 7. Speed, freshness and consistency

| Property | Target |
|---|---|
| <Volume> | <e.g. 100,000 accounts> |
| <Speed> | <99 of every 100 answered within 10 milliseconds> |
| <Change visibility> | <a saved change is reflected within 60 seconds, end to end> |

> Plain numbers, no jargon. Then the guarantees that are not numbers:
>
> - **Do the targets hold while things are changing?** A system that is fast only at rest has not met
>   the requirement — say so explicitly or it will be measured at rest.
> - **What does one answer reflect?** One coherent moment, or a mixture.
> - **How long may an answer be reused**, and what that bound is a condition of.
> - **Where is the promise stated to the user?** A working-as-designed delay nobody was warned about
>   is indistinguishable from a fault.
>
> If this feature adds a slow path (an operator investigation, an outside service), state that it
> shares no fate with the fast path and that the earlier demonstration must produce identical results
> with the feature deployed.

---

## 8. Change management

> What is recorded for every change: **who** (a person, or the upstream system), **when**, **what**
> (before and after), **why** (mandatory free text where a reason is owed).
>
> Then: the history only ever grows, what it is filterable by, and how long it is retained — with the
> retention justified by the business records it has to outlive, not by a round number.
>
> If a change can reach many customers at once, say what the operator is shown before saving. Editing
> one exception and editing a plan are not the same act and the document must not treat them as one.

---

## 9. The UI

> The screens, numbered, one line each: what an operator does there. For an extension, list only the
> screens that change and say the rest are untouched.

| Role | Can do |
|---|---|
| | |

> Roles if this feature has any. Then any presentation rule the specification genuinely requires —
> grouping, collapsing, search — with the reason it is required rather than nice.

---

## 10. Amendments to <the earlier specification> *(extensions only)*

> A first specification of a thing has nothing to amend and deletes this section, closing the gap —
> 001 and 003 both run to twelve sections, 002 to thirteen.

This feature changes <N> things the <earlier> specification states. They are listed here so the
change is deliberate and reviewable rather than discovered later.

| <Earlier> states | Now |
|---|---|
| <The claim, with its section reference> | <What replaces it, and what stays true> |

Everything else in the <earlier> specification stands, including <name the load-bearing rules that
are explicitly unaffected>.

---

## 11. Acceptance criteria

The <specification is satisfied / feature is complete> when every criterion below can be demonstrated.

> Numbered contiguously from 1 through the whole section, grouped under subheadings by theme
> (Model · Decisions · Explanations · Speed · Change safety · UI). The numbers are permanent: they
> are cited from code, plans and reviews as `(cNN)`, so never renumber — append.
>
> Each criterion is one demonstrable fact, phrased so a reader can tell whether it happened. *"The
> most generous GRANT wins among several, regardless of creation order"*, not *"grants are handled
> correctly"*. Write the negative ones too: what must **not** happen, and what must be an error
> rather than a denial.

### <Theme>

1. <Criterion.>
2. <Criterion.>

### Definition of done

> Who must be able to demonstrate these, and with what — *"by someone with no access to internals"*.
> Name any criterion needing a demonstration rather than a test, and the conditions it runs under:
> *"evidenced at the stated volumes, against data that is changing during the demonstration"*.
> A condition left unstated is a condition that will be dropped.

---

## 12. Deliberately left to the technical implementation plan

> Real decisions excluded from this document because they are engineering choices rather than
> business rules. For each, **state the business posture as settled and hand over only the
> mechanism**:
>
> - **<The question.>** The posture is fixed: <what must be true for the business>. How that is
>   honoured is engineering's to choose.

---

## 13. Known limitations

Accepted knowingly, each with its resolution in [`future-spec.md`](../future-spec.md):

| Limitation | Consequence |
|---|---|
| <What the feature cannot do> | <What that costs, in the business's terms — the customer who is affected, the manual step somebody now owns> |

> The consequence column is the point. A limitation without its consequence is a shrug; with one, it
> is a decision somebody can overturn later on evidence. Anything that will look like a bug to a
> reader who has not read this document belongs here, said plainly.

---

> ## House conventions for `.specs/`
>
> Delete this block along with the rest of the guidance.
>
> - **One directory per feature**, `.specs/NNN-feature-slug/`, holding this `spec.md` plus every
>   artifact derived from it: `plan.md`, `research.md`, `data-model.md`, `contracts/`. Only
>   `future-spec.md` sits at the `.specs/` root, because it spans all features.
> - **`spec.md` is the source of truth.** Where it and any other document disagree, it wins.
> - **Deferrals go to `future-spec.md`**, each with what it is, why it was deferred, what should
>   trigger building it, and what it depends on. An item removed from that list is recorded as
>   withdrawn, so its absence reads as a decision rather than an omission.
> - **Numbers are permanent.** Feature numbers, criterion numbers and `future-spec` item numbers are
>   cited from code and other documents. Append; never renumber.
> - **Business decisions taken during review go to `DECISIONS.md`** at the repository root, dated.
> - **Check link depth after copying.** This template sits at `.specs/`; your `spec.md` will sit one
>   level deeper, so its links read `../future-spec.md` and `../001-entitlement-service/spec.md`. A
>   wrong depth resolves silently outside the repository rather than failing.
