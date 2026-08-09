# Entitlement Service — Deferred Scope

**Status:** Draft for review
**Date:** 2026-08-09 (revised)
**Companion documents:** [`spec.md`](./001-entitlement-service/spec.md) — the v1 specification · [`001-entitlement-service/plan.md`](./001-entitlement-service/plan.md) — how v1 is being built

Everything here was considered during the v1 design and deliberately left out. Nothing in this document is an oversight. Each item records what it is, why it was deferred, what should trigger building it, and what it depends on.

Both questions this document previously left open with engineering have since been answered, and neither needs revisiting. The fixed outage posture — an outage neither takes away nor grants, and products carry on with the last answer they saw — is honoured by giving every product its own copy of the rules and the values, so it can keep answering on its own while the service is unreachable. A plan's past values are kept by recording every edit in the change history rather than by storing distinguishable versions of the plan; the business rules behind both remain as stated in [`spec.md`](./001-entitlement-service/spec.md) §11 and §3.2.

## What building v1 changed about this list

Three things about the way v1 is being built change what the items below cost, and in two cases what they depend on. They are recorded once here rather than repeated twelve times.

**Every product now carries its own copy of the rule.** This is what makes the outage posture real rather than promised, and it is the right trade. The consequence for this list is that changing *how values combine* is no longer a change to one system. Every product has to take the new rule, and until it does it stops answering rather than answering differently from its neighbours. Items 1, 3, 5 and 8 all change how values combine, so each now carries a coordinated release across every consuming product — a cost that did not exist when this document was written.

**Operator sign-in is not built.** v1 ships with no login and no roles, by decision, on the understanding that the service stays on a trusted network until that changes. Two items here are entirely about *who may do a thing* — items 2 and 9 — and neither is expressible while every action is recorded against a single stand-in operator. Sign-in is now a hard dependency for both, and the risk item 2 addresses is larger than that item was originally written to describe.

**Products receive answers; only the operator tool receives explanations.** The reasons behind a decision — who granted it, when, and why — never leave the operator tool. This was decided partly *because* of item 11's caution, and it makes that item safer. It also constrains item 6, which needs to know what kind of denial it is looking at.

## Priority at a glance

| # | Item | Why it matters | Blocked by | Suggested priority |
|---|---|---|---|---|
| 1 | Time-bounded overrides | Every temporary promise is currently permanent | — | High |
| 2 | Protected HOLDs | Compliance suspensions can be lifted by anyone | Operator sign-in | High |
| 3 | Relative grants | Contractual "plan + 20" promises evaporate on upgrade | — | Medium |
| 4 | Plan versioning and rollback | No recovery from a bad plan edit | An unanswered design question | Medium |
| 5 | Bulk and segment overrides | Forty accounts means forty manual records | — | Medium |
| 6 | Upgrade path on denials | Every product rebuilds its own upsell mapping | — | Medium |
| 7 | Override categories | Commercial and compliance exceptions are indistinguishable | — | Medium |
| 8 | Capability dependencies | Nonsensical combinations can be saved | — | Low |
| 9 | Approval workflow for plan edits | Thousand-account changes need no second opinion | Operator sign-in | Low |
| 10 | Plan inheritance | Plan definitions duplicate one another | — | Low |
| 11 | Customer-facing entitlement view | Self-service visibility | — | Speculative |
| 12 | Affected-account warning for capability edits | Default and off-value edits reach every unset account with no warning | — | Medium |

---

## 1. Time-bounded overrides

**What:** A start date and an expiry date on any override. On expiry, the plan value resumes automatically. Operators are warned before an override lapses, and expiry is visible in the explanation.

**Why deferred:** v1 chose open-ended overrides to keep the model small. Scheduling introduces a second question the service must answer — "what applies *now*" versus "what will apply" — which roughly doubles the evaluation surface.

**Why it matters:** Almost every sales exception is temporary in intent. "Pro features free for 90 days" and "pilot access this quarter" are the common cases, and today each one becomes a permanent leak that nobody remembers to revoke. This is the single largest gap in v1.

**Changed since v1 was planned:** two things, and both make this item bigger than it looked.

The first is the coordinated release described above — an expiry changes which exceptions count when values are combined, so every product has to take the change together.

The second is subtler and specific to this item. **An expiry is the first change in the system that happens because time passed rather than because a person acted.** Everything v1 tells products about is triggered by someone saving something; nothing yet says "the clock moved". An override that lapses at midnight changes a customer's answer with nobody having touched it, so how that reaches the products has to be designed rather than assumed. Related: v1's answers depend only on what exists at the moment of the decision, never on when anything was created — a deliberate property that makes the same facts always produce the same answer. Introducing time into the decision itself relaxes that property, and the checks that keep products honest with one another would have to account for it.

**Trigger:** As soon as the first temporary promise is made — which is likely to be immediately.

**Depends on:** Nothing. Should still be first.

**Interacts with:** Item 3. A relative grant with an expiry is the natural shape of a contractual pilot, and designing the two together means one coordinated release rather than two.

---

## 2. Protected HOLDs

**What:** Restrictions on who may remove a HOLD. Two candidate designs:

- **Simple** — anyone with override rights may create a HOLD, but only an administrator may remove one.
- **Categorised** — a HOLD is tagged (compliance, billing, commercial) and only someone in that category, or an administrator, may remove it. Billing automation could not clear a legal hold, and vice versa.

**Why deferred:** v1 accepted the risk to avoid building a permission matrix before the roles are proven in use.

**Why it matters:** v1's rules make a HOLD impossible to *out-vote* — no GRANT can defeat it. They do nothing to stop it being *deleted*. An account manager blocked by a compliance suspension can simply remove it, with only an audit entry after the fact. The scenario is concrete: legal suspends data export during an investigation; an account manager, unaware, deletes the hold to close a renewal.

**Changed since v1 was planned:** the exposure is wider than this item was written to describe, and the item has gained a dependency.

v1 ships with no operator sign-in at all. So it is not merely that anyone *with override rights* may remove a compliance suspension — anyone who can reach the tool may, and may equally add one. This is a known and accepted condition of the MVP, and the service is to stay on a trusted network until sign-in lands. It also makes sign-in a hard prerequisite here: "only an administrator may remove this" cannot mean anything while every action is attributed to a single stand-in operator.

What v1 *did* ship is the visibility half of this problem. Saving an override immediately shows the resulting decision and its full explanation, so an operator who grants something that a suspension is capping sees that at once instead of wondering why nothing happened. And the confirmation for removing a HOLD states plainly that removal is permitted, recorded, and unrestricted — the gap is put in front of the person at the moment it matters, rather than left to be discovered.

**Trigger:** Before the first genuine compliance or trust-and-safety suspension is placed through this service, and no later than the moment sign-in lands. Do not wait for an incident.

**Depends on:** Operator sign-in. Item 7 as well, if the categorised design is chosen.

---

## 3. Relative grants

**What:** A grant expressed as an adjustment to the plan value — "20 seats more than whatever their plan gives" — rather than an absolute number.

**Why deferred:** v1 grants are absolute, which keeps the resolution arithmetic simple and comparable across grants.

**Why it matters:** Contracts are frequently written relatively. Stored as an absolute 70 (plan 50 + 20), the promise silently evaporates the moment the plan moves to 100 — the account gets 100, not the 120 they are contractually owed, and nothing flags the loss. The v1 plan-floor rule is correct and is precisely what causes this.

**Open question for design:** how relative and absolute grants compare when both exist. "Most generous" needs a definition once one grant's value depends on the plan.

**Changed since v1 was planned:** the open question above is sharper than it looked, and this item carries the coordinated release described above.

Every value in v1 sits somewhere on a single ladder from least to most generous, and that ladder is what makes "most generous" answerable at all. A relative grant has no place on that ladder until the plan's value is known, so it is not simply another kind of value — it is a promise about a value. That is why the comparison question is a design question rather than a detail, and it should be answered before the feature is scheduled, not during it.

**Trigger:** The first contract written as "plan plus N".

**Depends on:** Nothing, but should be designed together with item 1 so that one coordinated release covers both.

---

## 4. Plan versioning and rollback

**What:** Each plan edit creates a version that can be viewed, compared and reverted.

**Why deferred:** v1 rejected it deliberately. "Revert this override" is deleting a record. "Revert this plan edit" is not the analogous operation — accounts move between plans in the meantime, so replaying an old version can grant capabilities to accounts that were never meant to have them. A rollback button that is unsafe in the common case is worse than no button.

**Why it matters:** There is currently no recovery from a bad plan edit beyond manually reconstructing the previous values from the audit trail.

**What to design first:** what "revert" means for accounts that joined or left the plan after the edit. Without an answer, this feature should not be built. This remains the only genuine blocker on the item.

**Changed since v1 was planned:** this item previously expected that versioning might fall out naturally from how a plan's past values came to be recorded. **That decision has since been made, and it went the other way.** A plan keeps one current set of values, and its past is reconstructed from the change history rather than stored as versions — chosen precisely because stored versions would exist mainly to serve a rollback the specification forbids.

Nothing about that choice blocks this item, but nothing delivers it either. It now has to be built deliberately, and the change history is the material it would be built from: every edit records what the values were before and after, who made it, when, and how many accounts it reached. That is enough to reconstruct any past state and to show a comparison; it is not enough, on its own, to make reverting safe — which is what the design question above is for.

**Industry note (2026-08-09):** the mature pattern at dedicated entitlement platforms (Stigg, Chargebee) is a per-change audience choice at publish time — "apply to everyone" or "new accounts only", where the latter forks a successor plan automatically. If this item is built, that choice is its natural shape. The v1 business rule in [`spec.md`](./001-entitlement-service/spec.md) §3.2 — edits apply to everyone — remains the default either way, and v1 offers no audience choice at all, so introducing one is a change to how a plan edit is saved and not only to what is stored.

**Depends on:** The design question above, and nothing else.

---

## 5. Bulk and segment overrides

**What:** Applying an exception to a defined group of accounts at once — everyone from an acquisition, everyone in a pilot programme, everyone in a partner tier — as a single record rather than one per account.

**Why deferred:** It adds a resolution layer between plan and per-account override, and a definition of what a segment is and who maintains it.

**Why it matters:** These promises are made routinely and today become dozens of hand-created records that must be found and removed individually.

**Design note:** A segment override sits between the plan and per-account overrides in generosity terms; the GRANT/HOLD rules should extend to it without a new tiebreak, but this needs confirming rather than assuming.

**Changed since v1 was planned:** this item now has a *running* cost, not only a build cost — and it is the only one on this list that does.

What every product carries stays small because a hundred thousand accounts share about ten plans, so one plan edit is a tiny change however many accounts it touches. Segment membership does not compress that way: which accounts are in a segment is a fact about each account. A segment layer therefore grows what every consuming product has to hold and keep current, in proportion to how widely segments are used. That is affordable and worth paying for, but it should be a decision rather than a surprise, and it argues for segments being few and deliberate rather than an ad-hoc grouping anyone can create.

It also changes how values combine, so it carries the coordinated release described above.

**Depends on:** Nothing, though it compounds badly with item 1 if built first — bulk overrides that never expire are a large leak.

---

## 6. Upgrade path on denials

**What:** When the answer is not-allowed, also return the lowest plan that would grant the capability, so a product can show "available on Pro" instead of a dead end, and sales can see immediately what to sell.

**Optional refinement:** a per-capability "may be advertised" flag, so unreleased or compliance-gated capabilities never leak into an upsell.

**Why deferred:** Not needed to answer the core question, and it adds a field every caller must decide how to treat.

**Why it matters:** Without it every product builds its own mapping of capability to upsell plan — the scattered-logic problem this service exists to remove, reappearing one layer up.

**Caution, revised:** this feature must distinguish "your plan does not include this" from "you are suspended" and from "you have none left". Two of those three are now cleanly distinguishable, and the third has become permanently the caller's job.

v1's explanation already separates the cases this feature has to tell apart — whether the plan set the value or it fell back to a default, whether a suspension is capping the result, whether no exception exists at all. What the service can never tell you is that a customer has *used* all of theirs, because it publishes limits and never knows consumption; the item that would have changed that has been dropped from this list. So an upgrade prompt must be built to stay silent on allowance, and the product holding the count remains the only thing that can speak to it.

**Changed since v1 was planned:** the item splits cleanly in two, and only one half is hard.

*Which plan would grant this capability* is knowable from the plan definitions every product already holds, so that half could be answered anywhere. *Whether this particular denial is the kind worth prompting on* needs the explanation, and explanations live only in the operator tool. Deciding where the prompt is composed is therefore the real design choice here, and it should be made before the field is added rather than after.

**Depends on:** Nothing.

---

## 7. Override categories

**What:** Tagging each override with its origin — commercial, compliance, billing, support — for reporting, filtering, and as the basis for the categorised design in item 2.

**Why deferred:** Adds a field and a taxonomy that would have to be guessed before the roles are proven in use.

**Why it matters:** In v1, "contractually agreed lower limit" and "suspended for fraud" are both HOLDs with a free-text reason. The arithmetic is correct, but they cannot be reported on separately, and no rule can treat them differently.

**Changed since v1 was planned:** two practical notes, one favourable and one not.

The favourable one: a category is information *about* an exception rather than part of the answer, which puts it in the same class as the reason text — it stays in the operator tool and costs the consuming products nothing. That holds only for as long as no rule treats categories differently when combining values. The moment one does, this item joins items 1, 3, 5 and 8 in needing a coordinated release.

The unfavourable one: overrides in v1 cannot be edited. Correcting one means removing it and creating a replacement, each with its own reason. So categories cannot simply be added to the exceptions that already exist — either a one-off exercise assigns them, or everything created before this feature stays uncategorised and reporting has a blind spot for that period. Worth deciding up front, because it gets worse the longer the item waits.

**Depends on:** Nothing. Is a prerequisite for the categorised form of item 2.

---

## 8. Capability dependencies

**What:** Declaring that one capability requires another — SCIM provisioning is meaningless without SSO — and warning or blocking when a plan or override would create an invalid combination.

**Why deferred:** Nonsensical combinations are currently caught by the operator, not the system. The failure is confusing rather than dangerous.

**Design note:** Validate at the moment of saving, in the operator tool, rather than at the moment of deciding.

**Changed since v1 was planned:** that design note is now a much stronger recommendation than it was.

Enforcing a dependency when a decision is made would mean one capability's answer depends on another capability's value. Beyond complicating a rule whose simplicity is the point, it would move this item into the coordinated-release category above — every product would need the new rule at once, for a feature whose entire value is stopping an operator from saving something silly. Checking at the moment of saving keeps it a change to the operator tool alone, which is where the mistake is actually made.

**Depends on:** Nothing.

---

## 9. Approval workflow for plan edits

**What:** Plan-level edits enter a pending state until a second authorised person approves. Per-account overrides stay one-click.

**Why deferred:** v1 chose blast-radius warnings plus the audit trail instead. Speed is the reason exceptions exist, and a review queue that blocks urgent fixes has its own cost.

**Why it matters:** A plan edit can change thousands of accounts in a single click, and the safeguard is procedural rather than a second pair of eyes.

**Changed since v1 was planned:** the existing safeguard is stronger than this item states, and the item has gained a dependency.

v1 does not merely *display* how many accounts a plan edit will reach. It refuses to save an edit whose reach has not been calculated and shown to the operator first, requires them to preview the effect on a named account, and records the number alongside the change so a past edit stays legible months later. The reach of a plan edit therefore cannot be unknown at the moment it is made. What is still missing is a second person — which is a real gap, and a smaller one than it was.

The dependency: a second *authorised* approver cannot exist while there is one stand-in operator, so this waits on sign-in.

**Also needs:** an emergency path, or the queue becomes the outage.

**Depends on:** Operator sign-in.

---

## 10. Plan inheritance

**What:** A plan builds on another — "Enterprise is Pro plus these changes."

**Why deferred:** v1 keeps plans flat because inheritance makes explanations recursive, and the explanation is the product.

**Why it matters:** Purely an authoring convenience. Today, shared values are restated in each plan and can drift apart.

**Design note:** If built, the trace must still read as a flat list of steps. An explanation that requires understanding the plan hierarchy to interpret defeats the purpose of the service.

**Changed since v1 was planned:** flatness is now a property of what is recorded, not only of what the editor offers — there is nowhere to note that one plan derives from another, anywhere in the system. Adding inheritance is therefore a change to the record itself and to every surface that reads it, which is a larger act than this item implied. The design note above also hardens into a requirement: an explanation begins by saying either "the plan sets this" or "the plan is silent, so the default applies", with nothing in between. Inheritance would have to resolve to one of those two before the explanation is written, or that shape has to change — and the shape is the thing operators have learned to read.

**Depends on:** Nothing.

---

## 11. Customer-facing entitlement view

**What:** Exposing an account's own entitlements to that customer, in-product or via self-service.

**Why deferred:** v1 is an internal service with an internal UI.

**Caution:** Explanations are written for internal operators and name internal reasons — "suspended pending investigation", "goodwill grant, renewal risk". Any customer-facing surface needs a separate, sanitised presentation.

**Changed since v1 was planned:** v1 acted on this item's caution in advance, which makes the item safer and narrows what the caution applies to.

Internal reason text never leaves the operator tool. Products receive values and nothing else — not the reason, not who set it, not when. So a customer-facing surface built on what a product already holds cannot leak an internal reason, because the product never had one. The caution now applies to exactly one case: a surface built directly against the operator tool's own explanation, which remains written for internal readers and must not be shown to customers as-is.

**Depends on:** Item 6 is a natural pairing — a customer-facing view is where the upgrade path is most valuable, and where the "may be advertised" flag stops being optional.

---

## 12. Affected-account warning for capability edits

**What:** The affected-account count and single-account preview that guard plan edits, extended to two further edits with wide reach: changing a capability's default value, and changing its off-value. A default edit reaches every account whose plan does not set that capability; an off-value edit changes what counts as *not available* for every account already sitting at that value.

**Why deferred:** Decided 2026-08-09 during plan review: v1 accepts the gap. Defaults and off-values are expected to be set when a capability is declared and rarely touched afterwards, and until operator sign-in arrives the operator population is one trusted person.

**Why it matters:** A default edit is the only far-reaching change in the system that states nothing before saving — it can touch more accounts than any plan edit, precisely because it applies wherever no plan speaks. An off-value edit is subtler: it flips the yes/no answer for accounts whose value has not moved at all.

**Changed since v1 was planned:** the asymmetry is now sharp enough to name, and the two halves of this item turn out to cost different amounts.

The plan editor cannot save an edit without having calculated and shown its reach. The capability editor accepts a change to a default or an off-value with no such step at all — the same operator, minutes apart, is stopped in one place and waved through in the other. That inconsistency is the strongest argument for building this item, and it is also why the work is small on the default half: the counting and preview already exist and would be pointed at a second kind of edit.

The off-value half is a different question. Counting who a default edit reaches is "who is on a plan that does not set this" — the same kind of question the plan editor already answers. Counting who an off-value edit reaches is "whose answer currently sits at this value", which has to be worked out account by account rather than looked up. Still perfectly feasible at v1's stated volumes, but it is a different calculation and should be scoped as one.

**Trigger:** Before capability administration is opened to more than one operator — which is now the same moment sign-in lands — or the first time a default edit surprises anyone.

**Depends on:** Nothing. The plan editor's existing preview is the natural foundation for the default half.

---

## Removed from this document

Five items were withdrawn on 2026-08-09. They are recorded here so their absence reads as a decision rather than an omission:

| Was # | Item | 
|---|---|
| 2 | Stale override review after a plan change |
| 3 | Existing-override warning in the UI |
| 11 | Account hierarchy |
| 14 | Unordered choice values |
| 15 | Usage-aware decisions |

Two consequences are worth carrying forward. A HOLD created under an old plan can still outlive an upgrade and keep suppressing something the customer now pays for; that is no longer treated as a limitation awaiting a fix but as an accepted condition, visible in the account view and cleared by an operator's judgement ([`spec.md`](./001-entitlement-service/spec.md) §3.4). And because usage-aware decisions are withdrawn, this service permanently publishes limits and never knows consumption, which is what item 6's revised caution turns on.
