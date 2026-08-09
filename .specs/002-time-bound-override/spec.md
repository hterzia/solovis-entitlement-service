# Time-bounded Overrides and Point-in-Time Answers — Business Specification

**Status:** Draft for review
**Date:** 2026-08-09
**Companion documents:** [`001-entitlement-service/spec.md`](../001-entitlement-service/spec.md) — the v1 specification this extends · [`future-spec.md`](../future-spec.md) — deferred scope, item 1

---

## 1. Purpose

Almost every override the business grants is temporary in intent. *Pro features free for ninety days.* *Pilot access this quarter.* *Suspended until the invoice clears.* The v1 service can express none of that: an override lasts until a person remembers to remove it, so every temporary promise becomes a permanent leak, and the only defence is somebody's diary. This is the single largest gap in v1, recorded as the first item of deferred scope.

This feature gives an override a beginning and an end.

It also answers a question v1 cannot answer at all: **what could this customer do on a date in the past?** Disputes, credit notes and support investigations all arrive in that form — *how many reports could Acme export last month* — and today the only way to answer is for a person to read the change history and reconstruct it by hand. That is a derivation nobody performs routinely, which means it is wrong on the day it matters.

The two belong in one feature because they are the same fact seen from two ends. An override with a beginning and an end **is** a record of what applied and when.

---

## 2. Scope

### In scope

- An optional start date and an optional expiry date on any override
- Overrides that have not begun, and overrides that have ended, both visible and distinguishable from those in force
- Overrides whose record survives their ending, and survives their removal
- A decision that takes account only of the overrides in force at the moment it is made
- An explanation that names overrides which are not in force, and why, so a dropped answer explains itself
- The operator checker extended to ask about a past date, with the full explanation as it stood
- The change history extended so a past decision can be reconstructed faithfully

### Out of scope

These are named because they are the things people will otherwise assume are included.

| Not in scope | Why |
|---|---|
| **Warning anyone before an override ends** | v1 has no notifications and no sign-in, so there is nobody to notify. Accepted knowingly; see §11. |
| **Relative grants** | *"Twenty seats more than their plan"* remains unexpressible. A separate deferred item, unchanged by this one. |
| **Past answers for whole accounts, or across a range of dates** | One account, one capability, one date. A quarter's worth of daily answers is a report, and a different feature. |
| **Products asking about the past** | Products receive current values, as they always have. Nothing about what a product holds or how it answers changes. |
| **Scheduled plan changes** | Only overrides gain dates. A plan edit still applies to everyone on the plan the moment it is saved. |
| **Automatic tidying of ended overrides** | Nothing is ever cleared by the system. Records that vanish are the reason the past is unanswerable. |

---

## 3. What changes about an override

### 3.1 The window

Every override — GRANT and HOLD alike — gains an optional **start date** and an optional **expiry date**. Both are plain dates. A start date begins at the beginning of that day; an expiry date runs to the end of it, so *"free until 31 December"* includes the whole of 31 December, which is what everyone who writes that sentence means.

Give neither and the override behaves exactly as it does in v1: in force from the moment it is saved until someone removes it. That is still the ordinary case, and nothing about existing overrides changes.

Dates are interpreted against **one clock used by the whole service: US Eastern time**. There is no per-operator and no per-customer clock — a date means the same thing to everyone who reads it. The clock is named on screen wherever a date is entered or shown, so nobody has to assume.

The one property this depends on is that **midnight must be unambiguous**. Eastern shifts an hour twice a year, but it does so at two in the morning, never at midnight, so *the end of 31 December* and *the start of 1 October* each name exactly one moment on every date of the year. Two days a year are twenty-three and twenty-five hours long, which changes nothing about which day an override is in force on.

Before saving, the operator is shown what the dates mean in words — *"in force from 1 October to 31 December inclusive"* — because a date field alone has never stopped anyone being off by a day.

A window whose start falls after its expiry describes nothing and cannot be saved. A window wholly in the past — an override created already ended — cannot be saved either: it would assert that something applied when it did not, and the past is now something this service answers questions about rather than something operators may write into. Back-dating a *start* into the past is likewise refused, for the same reason. An override begins no earlier than the moment it is saved.

### 3.2 The four states

At any moment an override is in exactly one state, and all four are visible in the account view:

| State | Counts towards decisions | Meaning |
|---|---|---|
| **Pending** | No | Its start date has not arrived. It exists and is visible, so a promise made in advance is not forgotten. |
| **In force** | Yes | The ordinary state. |
| **Ended** | No | Its expiry date has passed. It remains on the account. |
| **Removed** | No | A person removed it. It remains on the account, marked with the date of removal. |

An override may be removed in any of the first three states. Removing a pending override cancels a promise before it begins; removing an ended one is tidying, and changes no decision.

### 3.3 Overrides are still never edited

v1's rule holds unchanged: once an override is created, nothing about it is ever altered — from creation to removal it stays as it was written. Its window is part of it, and is no more editable than its value or its reason.

**Extending an override therefore means creating another one**, with a later expiry, alongside the first. Nothing is edited and nothing is removed. This needs no new rule to work: while both are in force the most generous GRANT wins and they agree, and when the first ends the second carries on alone. The same holds for a HOLD under the strictest-wins rule.

The consequence is deliberate and worth stating: an override extended three times leaves four records on the account and four entries in its explanation, because the explanation names every GRANT and marks the winner. Every one of those records describes a window that genuinely applied, which is precisely what makes the past answerable. The account view must group by state (§9) so that this reads clearly rather than as clutter.

---

## 4. How a decision is reached

The rule is unchanged. Only the set of overrides it applies to is narrower:

1. Start with the plan's value, or the capability's default if the plan does not mention it.
2. Take the most generous GRANT **among those in force at this moment**, if any.
3. Apply the most restrictive HOLD **among those in force at this moment**, if any.

Pending, ended and removed overrides take no part. Nothing else about the arithmetic changes: no new kind of value, no new comparison, no new tie-break. A window decides whether an override is in the room, never how loudly it speaks.

### What this preserves, and what it relaxes

Order-independence survives intact. It still does not matter which override was created first, or in what sequence they are considered.

What is relaxed is v1's stronger claim that a decision depends only on what exists and never on when. **The same facts now produce different answers at different moments**, which is the entire purpose of this feature. The v1 specification states the old property explicitly, so this document amends it rather than quietly contradicting it (§10).

One consequence follows for the checks that keep products honest with one another: two products comparing answers must compare answers *about the same moment*. Comparing an answer from before an expiry with one from after it is a difference that means nothing.

### An ending is a change nobody made

This is the first change in the system that happens because time passed rather than because a person saved something. An override that ends at midnight changes a customer's answer with nobody having touched it.

**An ending, and a beginning, are treated as changes like any other.** They reach every product within the same sixty seconds the v1 specification promises for a saved change, and they are recorded in the change history like any other change.

One consequence must be stated plainly, because it will otherwise be reported as a fault. The fixed outage posture is that products carry on with the last answer they saw. So **while the service is unreachable, an override that has ended goes on being honoured** — a lapsed free trial keeps working until the service can be reached again. That is not a defect; it is the outage posture doing exactly what it was chosen to do, applied to a change that happens to have been made by the clock. An outage neither takes away nor grants.

### What the explanation must show

Once answers can change because a date passed, *"why did this customer's access drop?"* becomes the commonest question the checker is asked — and it is unanswerable from an explanation that mentions only what counted. An explanation reading *"no GRANT in force"* is true and tells the operator nothing about the GRANT that ended last week.

**The explanation names every override on that capability that existed at the moment asked about, marking each as in force or not, and why not** — not yet begun, ended on a date, or removed on a date. Those not in force are visibly not counting, and the winner among those in force is marked as it is today.

This costs something and it is worth it. The v1 specification promises an explanation of no more than four lines; a much-extended override defeats that promise, and this document amends it (§10). Brevity was never the point — the explanation exists so that nobody has to guess, and a chain that hides the reason the answer changed fails at the one job it has.

For a past date, the same rule reads against that date: an override that did not yet exist then does not appear at all, and one that existed but was not in force appears marked as such.

---

## 5. Records are never destroyed

A past answer can only be trusted if every window that ever applied is still there to be read. Two rules follow, and the second amends v1.

- **An ended override is never cleared**, by the system or by the passage of time. It stays on the account until a person removes it, and removing it does not erase it either.
- **Removing an override ends its effect without erasing its record.** The record stays, marked with when it was removed and by whom.

The customer's answer behaves identically to v1 in both cases — a removed override stops counting the instant it is removed, and the value beneath it resumes with no further action. What changes is only that June remains answerable in July.

This amends v1, which describes removing an override as deleting it (§10).

---

## 6. Asking about the past

### 6.1 What is offered

The operator checker gains an optional date. Given an account, a capability and a date, it returns:

- **the value** as it stood at the end of that day
- **whether it was allowed**, on the same definition v1 uses
- **the explanation**, in the shape §4 defines: the baseline, every GRANT, every HOLD, each marked in force or not and why, which won among those in force, and the result

Ask without a date and the checker behaves exactly as it does today. Ask about **today** and the answer is the current one — the day is not yet over, so *as it stood at the end of that day* has no meaning yet, and the checker answers about now rather than guessing at midnight.

### 6.2 Who may ask

Operators of this service, in this service's own tool. Nowhere else.

Products receive values and never explanations, and each carries its own copy of the current rules — so a product has no material from which to answer a question about the past and is not asked to. Nothing about products changes, and no coordinated release across them is required by this half of the feature.

### 6.3 What the answer is assembled from

A past answer is built from four facts as they stood on that date, and then the ordinary rule of §4 is applied to them:

1. **Which plan the account was on** — from the change history
2. **What that plan set for that capability** — from the change history
3. **The capability's default and its off-value** — from the change history
4. **Which overrides were in force** — from the windows on the overrides themselves

The fourth needs no reconstruction at all, which is the whole reason these two halves are one feature.

### 6.4 What the change history must therefore provide

Two requirements fall out, and neither exists in v1.

**The history must be filterable by capability.** v1 makes it filterable by account, by plan and by actor. Facts 2 and 3 above are questions about one capability, and cannot be answered without it.

**The history must be retained for seven years, not twenty-four months.** v1 chose twenty-four months to cover a contract cycle plus renewal, which was the right bound while reconstructing the past was a manual exercise nobody performed. Now that answering *what could they do in March* is a stated feature that finance and support will rely on, the bound should match the commercial records those disputes turn on — credit notes, invoices and contracts are kept for seven years, and an entitlement history that expires first leaves exactly the questions it exists to answer unanswerable. This amends v1 (§10).

**The entries that establish a value must never be discarded.** Retention alone is not enough. If a plan's value for a capability was set eight years ago and never touched since, the entry that set it has aged out even under the longer bound, and *never set* becomes indistinguishable from *set before the record begins*. Whatever else ages out, the most recent entry establishing each current value is kept for as long as that value stands.

### 6.5 Never a confident wrong answer

A past answer that cannot be assembled honestly is refused, never guessed. In particular it must never fall back to today's value and present it as the past.

- **A date before the account existed** is answered as such, not as a denial.
- **A date beyond what the history covers** is answered as such, not as a denial. This is a real boundary and it is stated in the answer, not buried in a note.
- **A date in the future** is refused. This feature answers what was, not what will be — even though pending overrides make part of the future knowable, the plan side of it is not.
- **A capability retired since that date** is answered normally, with the fact of its retirement stated alongside. Retirement is a fact about now; it does not erase what was true then. This differs deliberately from asking about a retired capability today, which v1 treats as a clear error.

---

## 7. Speed and freshness

| Property | Target |
|---|---|
| A beginning or an ending reaching every product | Within **60 seconds** of the moment it takes effect — the same promise the v1 specification makes for a saved change |
| A past answer | 95 of every 100 within **3 seconds** |
| Every v1 speed target for current decisions | Untouched |

The last row is the important one. Current decisions are answered in milliseconds, thousands per second, by every product. A past answer is one operator investigating one dispute. The two must never share a fate: asking about the past adds no work to any current decision, and the v1 demonstration must produce the same results with this feature deployed as without it.

---

## 8. Change management

Everything v1 records, it goes on recording. Three additions:

- **A beginning and an ending are recorded**, with the moment each took effect and the override it belongs to. Because no person acted, the history records that the change was made by the passage of time rather than naming an operator — and it must be as plainly legible as any other entry, since *"who removed this?"* has *"nobody, it expired"* as a real answer.
- **A removal records that the override was removed**, not that it ceased to exist, consistent with §5.
- **The history is filterable by capability**, in addition to by account, plan and actor.

The history's existing character is unchanged: it only ever grows, and it cannot be edited or tidied. Its retention is extended from twenty-four months to **seven years** (§6.4), with the establishing entries of §6.4 kept for as long as the values they set still stand, however long that is.

---

## 9. The UI

Four screens change; the rest are untouched.

**Adding an override** — optional start and expiry dates, with what they mean shown in words before saving, and the existing promise of when the change will be live everywhere. Leaving both blank is the ordinary case and must remain the fastest path through the screen.

**Account view** — overrides grouped by state, with those in force shown first and prominently, and pending, ended and removed ones present but visibly not counting. An account with a long history of extensions must still read at a glance as *"this is what applies now"*.

**Checker** — an optional date. When a date is given, the screen states unambiguously that it is showing the past, so a historical answer is never mistaken for the current one. The explanation shows overrides that were not in force alongside those that were, visibly not counting and stating why — ended on a date, not yet begun, removed on a date (§4). Every explanation the checker renders is still the very explanation the decision produced, never a separately written account of it.

**Change history** — filterable by capability, and showing endings and beginnings alongside the changes people made.

---

## 10. Amendments to the v1 specification

This feature changes six things the v1 specification states. They are listed here so the change is deliberate and reviewable rather than discovered later.

| v1 states | Now |
|---|---|
| Overrides are open-ended — they last until someone removes them (§3.4) | Overrides may carry a start date, an expiry date, both, or neither. Neither remains the ordinary case and behaves as before. |
| A decision depends only on what exists at the moment of the decision, never on history (§4) | A decision depends only on what is **in force** at the moment of the decision. Order-independence is unchanged; the same facts now yield different answers at different moments. |
| Evaluating the same state twice in any order produces the same result (criterion 16) | Evaluating the same state at the **same moment** produces the same result, in any order. |
| Removing an override is deleting it (§8) | Removing an override ends its effect immediately and retains its record, marked with when and by whom. The effect on the customer is identical. |
| The explanation is never more than four lines (§4) | The explanation also names overrides that existed but were not in force, marked with why. It is as long as it needs to be to say why the answer is what it is. |
| History is retained for at least twenty-four months (§8, criterion 33) | Seven years, to match the commercial records the disputes it answers turn on — and the entries establishing current values for as long as those values stand. |

Everything else in the v1 specification stands, including the definition of *allowed*, the ordering of values, the outage posture, and the rule that a plan edit applies to everyone on the plan.

---

## 11. Acceptance criteria

The feature is satisfied when every criterion below can be demonstrated.

### Windows

1. An override can be saved with a start date, an expiry date, both, or neither, and one saved with neither behaves exactly as it does today.
2. An override whose start date has not arrived is visible, is marked as not yet in force, and takes no part in any decision.
3. An override whose expiry date has passed is visible, is marked as ended, and takes no part in any decision.
4. An expiry date includes the whole of the day named: on that date the override still counts; on the following date it does not.
5. Dates are interpreted against US Eastern time everywhere in the service, that clock is named wherever a date is entered or shown, and a window spanning a daylight-saving change is in force on exactly the days it names.
6. Before saving, the operator is shown in words what the dates mean.
7. A window whose start falls after its expiry cannot be saved; nor can a window wholly in the past, nor a start date earlier than the moment of saving.
8. An override cannot be edited, its window included; extending one means creating a second override alongside it.
9. Two overlapping overrides on the same capability resolve under the unchanged rules — most generous GRANT, then strictest HOLD — with no new tie-break, and the value does not change at the moment the earlier one ends.

### Decisions

10. A decision takes account of exactly the overrides in force at that moment, and of no others.
11. A GRANT that begins at a future date does not raise the value before that date, and does raise it from that date, with nobody acting.
12. A HOLD that ends does not restrain the value afterwards, with nobody acting, and any GRANT beneath it resumes with no further action.
13. While the service is reachable, a beginning and an ending each reach every product within 60 seconds of taking effect.
14. While the service is unreachable, a product goes on answering with the last value it saw, an ended override included — and this is demonstrated as intended behaviour, not repaired.
15. Every v1 speed and freshness demonstration produces the same results with this feature deployed as without it.

### Records

16. An ended override is never cleared by the system or by the passage of time.
17. Removing an override ends its effect immediately and retains its record, marked with when it was removed and by whom.
18. An account's overrides are viewable grouped by state, with those in force distinguishable at a glance from those pending, ended and removed.

### Explanations

19. The explanation names every override on that capability that existed at the moment asked about, marking each as in force or not — not yet begun, ended on a date, or removed on a date — with the winner marked among those in force.
20. Where an answer has changed because an override ended, the explanation alone is enough to say so: the operator need not open another screen to learn that an override existed and when it ended.
21. The explanation remains the one the decision produced, never a separately written account of it, however long it grows.

### The past

22. For an account, a capability and a past date, the checker returns the value, whether it was allowed, and the full explanation as it stood.
23. A past answer reflects the plan the account was on at that date, that plan's value at that date, the capability's default and off-value at that date, and the overrides in force at that date.
24. A past answer for a date on which a plan value has since changed differs from today's answer, and matches what was true then.
25. In a past explanation, an override created after that date does not appear at all, and one that existed but was not in force then appears marked as such.
26. A date before the account existed, and a date beyond what the history covers, are each answered plainly as such and never as a denial or as today's value.
27. A date in the future is refused, and a date of today returns the current answer.
28. A capability retired since the date asked about is answered normally, with its retirement stated.
29. 95 of every 100 past answers are returned within 3 seconds.

### History

30. A beginning and an ending each appear in the change history, recorded as made by the passage of time rather than by an operator, and are as legible as any other entry.
31. The change history is filterable by capability.
32. The change history is retained for seven years.
33. The entry establishing a current value is retained for as long as that value stands, however far beyond seven years that reaches.

### Definition of done

Every criterion above is demonstrable through the operator UI or the evaluation interface by someone with no access to internals. Criteria 11, 12 and 13 are demonstrated by letting the clock reach a boundary, not by editing anything.

---

## 12. Deliberately left to the technical implementation plan

- **How a beginning or an ending reaches products** within the promised sixty seconds, given that no person saved anything — including the two days a year on which a day is not twenty-four hours long
- **How the four facts of §6.3 are recovered** from the change history, and how that is kept honest as the history grows
- **How establishing entries are preserved** past the retention window without preserving everything

---

## 13. Known limitations

Accepted knowingly, each with its resolution deferred:

| Limitation | Consequence |
|---|---|
| Nobody is warned before an override ends | A customer can lose access with no one expecting it. The account view shows what is ending, but only to someone who looks. Requires operator sign-in before there is anyone to notify. |
| Past answers reach back only as far as the history | Seven years, and no further. Beyond that the answer is *cannot be determined*, which is honest but not useful. |
| The explanation grows | Naming overrides that are not in force makes a much-extended account's explanation long, and abandons v1's four-line promise (§10). Judged the right trade: an explanation that hides why the answer changed fails at its only job. |
| One account, one capability, one date | A dispute covering a quarter is answered one question at a time. |
| Extensions accumulate | A much-extended override leaves many records and a long explanation. All of them true; none of them tidy. |
| An ended override is still honoured during an outage | Required by the outage posture, and correct — but it will look wrong to anyone who has not read §4. |
| Grants are still absolute, not relative | *"Twenty seats more than their plan"* remains unexpressible, and a contractual pilot still cannot be written the way it was negotiated. |
| Plan changes still cannot be scheduled | Only overrides have dates. A plan edit applies to everyone the moment it is saved. |
| Everything is US Eastern | An operator or customer elsewhere enters *31 December* and gets an end that falls hours either side of their own midnight — for a customer in Europe, mid-morning on 1 January. Correct, reproducible and deliberate; naming the clock wherever a date appears is the whole of the mitigation. A per-customer clock is a larger feature and is not deferred scope so much as a different product decision. |
| A mis-entered window cannot be put right retrospectively | Windows cannot be back-dated (§3.1), so an override saved with the wrong dates is corrected going forward only — by removing it and creating a replacement. The past keeps the window that actually applied, which is the point, but it means a typo is visible in the record for ever. |
