# Entitlement Service — Deferred Scope

**Status:** Draft for review
**Date:** 2026-08-08
**Companion document:** [`init-spec.md`](./init-spec.md) — the v1 specification

Everything here was considered during the v1 design and deliberately left out. Nothing in this document is an oversight. Each item records what it is, why it was deferred, what should trigger building it, and what it depends on.

Two decisions remain with engineering because they are mechanism rather than business rule: how products honour the fixed outage posture (an outage neither takes away nor grants — products keep the last answer they saw), and how a plan's past values are recorded. The business rules behind both were settled on 2026-08-09 and are stated in [`init-spec.md`](./init-spec.md) §11 and §3.2.

## Priority at a glance

| # | Item | Why it matters | Suggested priority |
|---|---|---|---|
| 1 | Time-bounded overrides | Every temporary promise is currently permanent | High |
| 2 | Stale override review after plan change | Customers can pay for capabilities an old hold still blocks | High |
| 3 | Existing-override warning in the UI | Operators act blind to what is already there | High |
| 4 | Protected HOLDs | Compliance suspensions can be lifted by anyone | High |
| 5 | Relative grants | Contractual "plan + 20" promises evaporate on upgrade | Medium |
| 6 | Plan versioning and rollback | No recovery from a bad plan edit | Medium |
| 7 | Bulk and segment overrides | Forty accounts means forty manual records | Medium |
| 8 | Upgrade path on denials | Every product rebuilds its own upsell mapping | Medium |
| 9 | Override categories | Commercial and compliance exceptions are indistinguishable | Medium |
| 10 | Capability dependencies | Nonsensical combinations can be saved | Low |
| 11 | Account hierarchy | Groups, resellers and workspaces cannot be modelled | Low |
| 12 | Approval workflow for plan edits | Thousand-account changes need no second opinion | Low |
| 13 | Plan inheritance | Plan definitions duplicate one another | Low |
| 14 | Unordered choice values | Only if a genuine non-set case appears | Speculative |
| 15 | Usage-aware decisions | Would fold consumption into the answer | Speculative |
| 16 | Customer-facing entitlement view | Self-service visibility | Speculative |
| 17 | Affected-account warning for capability edits | Default and off-value edits reach every unset account with no warning | Medium |

---

## 1. Time-bounded overrides

**What:** A start date and an expiry date on any override. On expiry, the plan value resumes automatically. Operators are warned before an override lapses, and expiry is visible in the explanation.

**Why deferred:** v1 chose open-ended overrides to keep the model small. Scheduling introduces a second question the service must answer — "what applies *now*" versus "what will apply" — which roughly doubles the evaluation surface.

**Why it matters:** Almost every sales exception is temporary in intent. "Pro features free for 90 days" and "pilot access this quarter" are the common cases, and today each one becomes a permanent leak that nobody remembers to revoke. This is the single largest gap in v1.

**Trigger:** As soon as the first temporary promise is made — which is likely to be immediately.

**Depends on:** Nothing. Should be first.

**Interacts with:** Item 5. A relative grant with an expiry is the natural shape of a contractual pilot.

---

## 2. Stale override review after a plan change

**What:** When an account moves plan, its overrides persist untouched (v1 behaviour), but the account view flags any override that is now redundant or contradictory — a GRANT the new plan already exceeds, or a HOLD that suppresses something the customer has just started paying for. Review and cleanup stay manual; nothing is deleted automatically.

**Why deferred:** v1 chose persistence because silently voiding promises is the worse failure. Flagging requires comparing every override against the new plan and deciding what "redundant" means for each value type.

**Why it matters:** The dangerous case is a HOLD created under an old plan that keeps blocking a capability after an upgrade. The customer has paid, the product says no, and nothing surfaces the reason.

**Trigger:** After the first support escalation caused by a stale hold — or proactively, since the failure is invisible until a customer complains.

**Depends on:** Nothing.

---

## 3. Existing-override warning in the UI

**What:** When an operator adds an override, the UI shows any override that already exists on the same account and capability — who created it, when, and why — and requires explicit confirmation before saving alongside it.

**Why deferred:** v1's resolution rules make overrides safe to coexist arithmetically, so this is a clarity feature rather than a correctness one.

**Why it matters:** The rules guarantee a sales GRANT cannot defeat a compliance HOLD, but they do not tell the salesperson that a HOLD exists. Without the warning, an operator saves a grant, sees no effect, and has no idea why. It is also the cheapest partial mitigation for item 4.

**Trigger:** Build alongside item 4; they address the same scenario from opposite ends.

**Depends on:** Nothing.

---

## 4. Protected HOLDs

**What:** Restrictions on who may remove a HOLD. Two candidate designs:

- **Simple** — anyone with override rights may create a HOLD, but only an administrator may remove one.
- **Categorised** — a HOLD is tagged (compliance, billing, commercial) and only someone in that category, or an administrator, may remove it. Billing automation could not clear a legal hold, and vice versa.

**Why deferred:** v1 accepted the risk to avoid building a permission matrix before the roles are proven in use.

**Why it matters:** v1's rules make a HOLD impossible to *out-vote* — no GRANT can defeat it. They do nothing to stop it being *deleted*. An account manager blocked by a compliance suspension can simply remove it, with only an audit entry after the fact. The scenario is concrete: legal suspends data export during an investigation; an account manager, unaware, deletes the hold to close a renewal.

**Trigger:** Before the first genuine compliance or trust-and-safety suspension is placed through this service. Do not wait for an incident.

**Depends on:** Item 9 if the categorised design is chosen.

---

## 5. Relative grants

**What:** A grant expressed as an adjustment to the plan value — "20 seats more than whatever their plan gives" — rather than an absolute number.

**Why deferred:** v1 grants are absolute, which keeps the resolution arithmetic simple and comparable across grants.

**Why it matters:** Contracts are frequently written relatively. Stored as an absolute 70 (plan 50 + 20), the promise silently evaporates the moment the plan moves to 100 — the account gets 100, not the 120 they are contractually owed, and nothing flags the loss. The v1 plan-floor rule is correct and is precisely what causes this.

**Open question for design:** how relative and absolute grants compare when both exist. "Most generous" needs a definition once one grant's value depends on the plan.

**Trigger:** The first contract written as "plan plus N".

**Depends on:** Nothing, but should be designed together with item 1.

---

## 6. Plan versioning and rollback

**What:** Each plan edit creates a version that can be viewed, compared and reverted.

**Why deferred:** v1 rejected it deliberately. "Revert this override" is deleting a record. "Revert this plan edit" is not the analogous operation — accounts move between plans in the meantime, so replaying an old version can grant capabilities to accounts that were never meant to have them. A rollback button that is unsafe in the common case is worse than no button.

**Why it matters:** There is currently no recovery from a bad plan edit beyond manually reconstructing the previous values from the audit trail.

**What to design first:** what "revert" means for accounts that joined or left the plan after the edit. Without an answer, this feature should not be built.

**Industry note (2026-08-09):** the mature pattern at dedicated entitlement platforms (Stigg, Chargebee) is a per-change audience choice at publish time — "apply to everyone" or "new accounts only", where the latter forks a successor plan automatically. If this item is built, that choice is its natural shape. The v1 business rule in [`init-spec.md`](./init-spec.md) §3.2 — edits apply to everyone — remains the default either way.

**Depends on:** The record-keeping decision in [`init-spec.md`](./init-spec.md) §11 — versioning may fall out of it naturally.

---

## 7. Bulk and segment overrides

**What:** Applying an exception to a defined group of accounts at once — everyone from an acquisition, everyone in a pilot programme, everyone in a partner tier — as a single record rather than one per account.

**Why deferred:** It adds a resolution layer between plan and per-account override, and a definition of what a segment is and who maintains it.

**Why it matters:** These promises are made routinely and today become dozens of hand-created records that must be found and removed individually.

**Design note:** A segment override sits between the plan and per-account overrides in generosity terms; the GRANT/HOLD rules should extend to it without a new tiebreak, but this needs confirming rather than assuming.

**Depends on:** Nothing, though it compounds badly with item 1 if built first — bulk overrides that never expire are a large leak.

---

## 8. Upgrade path on denials

**What:** When the answer is not-allowed, also return the lowest plan that would grant the capability, so a product can show "available on Pro" instead of a dead end, and sales can see immediately what to sell.

**Optional refinement:** a per-capability "may be advertised" flag, so unreleased or compliance-gated capabilities never leak into an upsell.

**Why deferred:** Not needed to answer the core question, and it adds a field every caller must decide how to treat.

**Why it matters:** Without it every product builds its own mapping of capability to upsell plan — the scattered-logic problem this service exists to remove, reappearing one layer up.

**Caution:** A product that shows "upgrade" on every denial will show it to suspended customers and to customers who are simply out of allowance. This feature must distinguish "your plan does not include this" from "you are suspended" and "you have none left", which means it reads the trace, not just the flag.

**Depends on:** Nothing.

---

## 9. Override categories

**What:** Tagging each override with its origin — commercial, compliance, billing, support — for reporting, filtering, and as the basis for the categorised design in item 4.

**Why deferred:** Adds a field and a taxonomy that would have to be guessed before the roles are proven in use.

**Why it matters:** In v1, "contractually agreed lower limit" and "suspended for fraud" are both HOLDs with a free-text reason. The arithmetic is correct, but they cannot be reported on separately, and no rule can treat them differently.

**Depends on:** Nothing. Is a prerequisite for the categorised form of item 4.

---

## 10. Capability dependencies

**What:** Declaring that one capability requires another — SCIM provisioning is meaningless without SSO — and warning or blocking when a plan or override would create an invalid combination.

**Why deferred:** Nonsensical combinations are currently caught by the operator, not the system. The failure is confusing rather than dangerous.

**Design note:** Validate at write time in the UI rather than at evaluation time. Enforcing dependencies during evaluation would make decisions depend on other capabilities' values and break the simplicity of the resolution rules.

**Depends on:** Nothing.

---

## 11. Account hierarchy

**What:** Parent and child organisations, reseller-managed accounts, and per-workspace entitlements within one account.

**Why deferred:** v1 defines an account as the billable customer, flat. Hierarchy means entitlements inherit downward and the resolution rules gain a dimension.

**Why it matters:** Required for enterprise groups with multiple subsidiaries, and for any reseller or partner-managed motion.

**Trigger:** The first customer that cannot be represented as a single billable account.

**Depends on:** Should be designed before item 7 if both are wanted — segments and hierarchies solve overlapping problems and should not be built independently.

---

## 12. Approval workflow for plan edits

**What:** Plan-level edits enter a pending state until a second authorised person approves. Per-account overrides stay one-click.

**Why deferred:** v1 chose blast-radius warnings plus the audit trail instead. Speed is the reason exceptions exist, and a review queue that blocks urgent fixes has its own cost.

**Why it matters:** A plan edit can change thousands of accounts in a single click, and the only current safeguard is that the UI states how many.

**Also needs:** an emergency path, or the queue becomes the outage.

**Depends on:** Nothing.

---

## 13. Plan inheritance

**What:** A plan builds on another — "Enterprise is Pro plus these changes."

**Why deferred:** v1 keeps plans flat because inheritance makes explanations recursive, and the explanation is the product.

**Why it matters:** Purely an authoring convenience. Today, shared values are restated in each plan and can drift apart.

**Design note:** If built, the trace must still read as a flat list of steps. An explanation that requires understanding the plan hierarchy to interpret defeats the purpose of the service.

**Depends on:** Nothing.

---

## 14. Unordered choice values

**What:** A value type that is a single choice among options with no natural ordering.

**Why deferred:** No genuine case was found. Every candidate examined — data residency, export formats, integrations, identity providers, notification channels, compliance attestations, cloud provider, model families, billing currency — turned out to be a *set* ("which of these may you use"), and a set is modelled cleanly as one switch per member.

**Why it should probably stay deferred:** "Most generous" and "most restrictive" require a total order. An unordered type would need its own resolution rule, giving up the order-independence that makes v1's model worth having.

**Trigger:** Only if a real single-valued unordered entitlement appears that genuinely cannot be modelled as a set. Re-examine the case carefully first — the set framing has covered every example so far.

---

## 15. Usage-aware decisions

**What:** The service reads consumption from a metering system and answers "38 of 50 used", or returns not-allowed when an allowance is exhausted.

**Why deferred:** v1 is deliberately a pure decision engine. Counting stays with the calling product, and this boundary is what keeps the service fast, correct under load, and simple to explain.

**Why it matters:** Callers currently combine two sources — the limit from here, the count from elsewhere — and each one does it slightly differently.

**Caution:** This is the single largest expansion of scope in this document. It introduces a freshness problem on the usage side, a dependency at read time, and reset-period semantics. It also changes what `allowed` means for quantities, which [`init-spec.md`](./init-spec.md) §5 deliberately settled. Do not take it on incidentally.

---

## 16. Customer-facing entitlement view

**What:** Exposing an account's own entitlements to that customer, in-product or via self-service.

**Why deferred:** v1 is an internal service with an internal UI.

**Caution:** Explanations are written for internal operators and name internal reasons — "suspended pending investigation", "goodwill grant, renewal risk". Any customer-facing surface needs a separate, sanitised presentation. It must not render the internal trace.

**Depends on:** Item 8 is a natural pairing — a customer-facing view is where the upgrade path is most valuable.

---

## 17. Affected-account warning for capability edits

**What:** The affected-account count and single-account preview that guard plan edits, extended to two further edits with wide reach: changing a capability's default value, and changing its off-value. A default edit reaches every account whose plan does not set that capability; an off-value edit changes what counts as *not available* for every account already sitting at that value.

**Why deferred:** Decided 2026-08-09 during plan review: v1 accepts the gap. Defaults and off-values are expected to be set when a capability is declared and rarely touched afterwards, and until operator sign-in arrives the operator population is one trusted person.

**Why it matters:** A default edit is the only far-reaching change in the system that states nothing before saving — it can touch more accounts than any plan edit, precisely because it applies wherever no plan speaks. An off-value edit is subtler: it flips the yes/no answer for accounts whose value has not moved at all.

**Trigger:** Before capability administration is opened to more than one operator — or the first time a default edit surprises anyone.

**Depends on:** Nothing. The plan editor's preview machinery is the natural foundation.
