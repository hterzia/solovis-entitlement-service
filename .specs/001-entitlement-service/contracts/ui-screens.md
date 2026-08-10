# Contract — Operator Screens (spec §9)

**Stack**: React 19 + TypeScript, Vite, TanStack Router + TanStack Query. Styling comes from the canonical `.claude/design/solovis/tokens.css`; no colour, radius or type value may be introduced that contradicts it.

Five screens, each backed by [`admin-api.md`](./admin-api.md). This file states what each screen must display and do, and which acceptance criterion it satisfies.

---

## Cross-cutting

### The liveness promise *(c41)*

Every save confirmation states when the change will be live everywhere: **"Saved. Active everywhere within 60 seconds."** The number comes from the service — `GET /admin/v1/meta` → `changeVisibleEverywhereWithinSeconds`, or the mutation's own response where it carries one — never a hard-coded literal, so the message and the guarantee cannot drift apart.

If that number is unavailable the confirmation still states **"Saved."** and omits the promise. It is never withheld pending a second, unrelated query: a *successful* write that renders nothing is worse than one that renders an incomplete promise, because the operator cannot distinguish it from a lost write and will repeat it. It appears on **every** mutation — capability create/edit/retire, plan edit, default-plan designation, plan assignment, override create and remove — because a working-as-designed delay that is not explained becomes a support ticket (§7).

Operators nonetheless see their own change immediately on re-read *(c30)*: mutations return the new `snapshotVersion` and the client invalidates its queries, so the account view and checker repaint from a snapshot that already contains the change.

### The refusal is as load-bearing as the promise

A save confirmation only means anything if its absence means something. **Every mutation renders the service's refusal when one comes back**, through one `<ErrorNotice>` component that prints the RFC 9457 `detail` (else `title`) in a `role="alert"` and never re-words it — the wire vocabulary is defined once, and a second account of the same failure is worse than none. The same applies to reads: a detail route whose entity 404s shows that error, never an indefinite "Loading…".

This is stated because it was once not true. The app carried eighteen mutations and rendered a failure for one of them, so a rejected write left the screen byte-identical to a successful one and the operator's only evidence either way was a banner that silently failed to appear.

It holds **while the operator keeps working**, which is the harder half. A write that is already in flight is not a stale result to be cleared and not a request that dismissing its form recalls: typing into a field mid-save, or collapsing the panel that launched it, must not discard the outcome. Both once did, and both silenced *success* as readily as failure — the write landing with no evidence at all is the failure mode that makes an operator repeat it.

### The console computes nothing

It renders what the service returns and maps enum values to labels — `CAPABILITY_DEFAULT` to `default`, `WINNING` to `winning`, an outcome to its phrase. It never re-derives an entitlement value. Where a screen needs an answer the resolver alone can give, that is a route on the service, not arithmetic in the client (`DECISIONS.md` §2).

### Trace rendering *(c24)*

`<TraceView trace={…} />` takes the `trace` object from the API response and renders it. It performs **no** resolution, no re-derivation, no re-wording of outcomes — it maps enum values to labels and nothing more. It reads as the four steps of §4:

```
  Baseline    Plan "pro" sets reports.monthly = 50
              └ or: Capability default = 0 (plan does not mention it)

  Grants      ✓ 200   ovr_4471  "Renewal concession — Q3 pilot"   j.okafor, 2 Jun
              ✗ 120   ovr_2210  "Migration goodwill"              lost — less generous
              → Most generous GRANT (200) beats the plan (50)

  Holds       ✓ 0     ovr_7788  "Suspended pending billing…"      billing-bot, 1 Aug
              → Most restrictive HOLD (0) caps the result

  Result      0   ·   allowed: true   (no off-value declared)
```

Denials render with the same weight and detail as grants, including denial by absence — "no GRANTs exist" is a printed line, not an empty region *(c23)*.

**This app is the only place in the estate where an explanation exists.** Consuming services resolve locally from a replica carrying values but no reason text, authorship or timestamps, so they can answer but never explain. Everything downstream of a "why is this customer seeing that?" question ends up here. Two consequences the build should take seriously: `<TraceView>` is a support tool, not decoration, and the management app wants decent uptime even though nothing depends on it for decisions.

### Roles *(c37 — not implemented in v1)*

The three roles of §9 are **not** enforced. Every screen is fully usable by anyone who can reach the app. Route definitions carry a `requiredRole` field that is currently unread, so enabling enforcement later is a guard, not a rewrite. Until then the UI shows a persistent banner: *"Unauthenticated instance — all actions are open and audited as `dev-operator`."*

---

## Screen 1 — Capability registry

**Route** `/capabilities` · **Will require** Viewer to read, Administrator to write

Declares capabilities and retires them.

- Capabilities render as a **tree grouped by area** with collapse and search *(c40)*. This is load-bearing, not cosmetic: sets are modelled one switch per member, so a twelve-region residency set is twelve rows, and a flat list of several hundred toggles is unusable (§9).
- Each row: key, display name, value type, default, off-value, status.
- **Create** — key, display name, description, value type, default, optional off-value; tier capabilities collect an ordered tier list (≥ 2). The form makes the §5 rules visible rather than only enforcing them: the off-value field is hidden for `SWITCH`, and constrained to `0` for `QUANTITY`.
- **Edit** — display name, description, default and off-value. Value type is displayed but not editable, with the reason on hover: "A capability has one value type across every plan" *(c1)*. The off-value editor obeys the same §5 rules as the create form — absent for `SWITCH`, constrained to `0` for `QUANTITY`, chosen from the declared tiers for `TIER`. Edits to a default or an off-value are **not** gated by an affected-account preview, which is the accepted limitation recorded in §12; that is a missing guard, not a missing field.
- **Tiers** — appended above the current maximum only. Reordering is not offered *(c3)*.
- **Retire** — a confirmation naming what stops being evaluable ("used by 2 plans, 14 live overrides"), and stating that retirement is permanent and that the capability stays visible in history *(c8)*. There is no delete control anywhere on this screen.
- Retired capabilities are shown, dimmed, behind a "show retired" toggle. They never simply vanish.

---

## Screen 2 — Plans

**Route** `/plans`, `/plans/{key}` · **Will require** Viewer to read, Administrator to write

- **List** — every plan with its account count and a "default for new accounts" marker *(c7)*. Archive is disabled with an explanatory tooltip when the count is non-zero *(c6)*, and when it *is* available it takes a confirmation before it fires — it is the most consequential control on the screen, and it should not be easier to trigger than designating a default.
- **Plan editor** — the same area-grouped, collapsible, searchable capability tree as screen 1 *(c40)*. Each capability shows either the plan's value or, greyed, the capability default with the label **"not set — falls back to default"**, which is how the partial-plan model (§3.2) is made visible rather than implied *(c4)*.
- **Saving is a three-step act, deliberately:**
  1. **Review** calls the preview endpoint and shows a banner that cannot be dismissed: **"This change affects 26,890 accounts."** *(c34)* Beside it, a diff of every capability being set, changed or unset.
  2. **Preview on one account** — the operator names an account and sees its before and after decision **with both traces** *(c35)*. The "no change for this account" case is called out explicitly, because a plan rise that is invisible to a suspended customer is exactly what an operator needs to learn before saving, not after.
  3. **Save** is enabled only once a preview has been fetched, and submits the `previewToken` it returned. A plan edit can touch thousands of accounts in one click; the UI must not treat that as the same act as editing one override (§8).
- No rollback control and no version history tab. §8 rejects plan rollback, and offering a button that is unsafe in the common case is worse than offering none.
- **Designate default plan** — a single control on the list screen; the change is confirmed and audited like any other *(c7, c32)*.

---

## Screen 3 — Account view

**Route** `/accounts`, `/accounts/{external}` · **Will require** Viewer to read, Exception manager to write overrides

- **Header** — account, plan, when it was assigned, by whom, and **whether by a person or an upstream system** *(c36)*.
- **Effective entitlements** — every non-retired capability in one place, area-grouped into a labelled band per area, each row marked with the source of its value in exactly that vocabulary: `default` · `plan` · `GRANT` · `HOLD` *(c39)*. The wire enum (`CAPABILITY_DEFAULT`) never reaches the operator. Override sources show the reason inline; clicking any row opens its full trace.
- **Overrides** — GRANTs and HOLDs listed with the capability they apply to, kind, value, reason, author, creation time, and current effect (`winning`, `overridden by a HOLD`, `superseded by a larger or newer GRANT`, `superseded by a stricter or newer HOLD`, `no effect — plan is more generous`, `no effect — not more restrictive than the result`). Multiple overrides on one capability are shown together as normal, not as a conflict, because §4 combines them safely.
- **Add override** — capability picker (area-grouped), kind, value, and a **reason field that blocks submission when empty** *(c9)*. After saving, the resulting trace is shown immediately, which is how an operator discovers that their GRANT is being capped by an existing HOLD.
- **Remove override** — confirmation showing what the value returns to, computed from the same resolver *(c14, c15)*. The confirmation for a HOLD says plainly that removal is permitted and audited but not restricted, so the known v1 gap (§8, `future-spec.md` §2) is visible at the moment it matters.

  The value is fetched from [`GET /admin/v1/accounts/{external}/overrides/{id}/removal-preview`](./admin-api.md), which re-resolves with that one override excluded and mutates nothing. The screen renders the number the service returned — *"This returns reports.monthly to 50."* — and does not work it out. Answering it here would mean a second implementation of §4's combining rule inside the SPA (`DECISIONS.md` §2).
- **Change plan** — a picker requiring the source (`person` / `upstream system`) *(c36)*, with a confirmation stating that overrides are retained, and how many.

> Not in v1, and deliberately absent from this screen: any expiry field on an override (`future-spec.md` §1). Two further absences are no longer tracked as deferred scope at all, having been withdrawn from that document on 2026-08-09 — a warning about overrides that already exist when adding a new one, for which the trace returned on save is the standing mitigation, and a staleness flag on overrides that survived a plan change.

---

## Screen 4 — Checker

**Route** `/checker` · **Will require** Viewer

The screen the whole service exists to make possible.

- Pick an account and a capability; get the decision and its explanation as a readable chain *(c38)*.
- Renders `<TraceView>` over the payload from `GET /admin/v1/check` — the very trace the evaluation returned *(c24)*.
- Shows `allowed`, `value`, `snapshotVersion` and `evaluatedAt`, so an operator can say exactly which moment they are looking at.
- The three §6.3 errors render as errors with their own wording — "No such account", "No such capability", "That capability is retired and is no longer evaluated" — and never as a denial *(c19)*.
- A "copy explanation" action yields the rendered chain as text, for pasting into a ticket. It copies what is on screen; it does not compose a second, prettier explanation. It also states whether the copy succeeded, and works on the origin the console is actually served from: `navigator.clipboard` exists only in a secure context, and this app runs over plain HTTP on an internal host, so the async API is absent exactly where it is needed and a selection copy is the fallback.
- Accepts an **override reference** (`ovr_9002`) in the search box as well as an account and capability. Consuming services carry these opaque refs in their debug logs and can carry nothing else, so pasting one here is the whole path from "a product logged something odd" to "here is why, who did it, and when" *(c38)*.

---

## Screen 5 — Change history

**Route** `/history` · **Will require** Viewer

- Filterable by **account**, **plan** and **actor** *(c33)*, plus a date range and entity type, newest first, cursor-paged.
- Each row: when, who, whether person or system, what changed, and before/after values *(c32)*.
- Plan-entitlement rows show the affected-account count recorded at the time *(c34)*, which is what makes a past plan edit legible months later.
- Override rows show the reason *(c9)*; removal rows show who removed it and when.
- Retired capabilities and archived plans appear by name and stay readable *(c8)*.
- **There is no edit, delete, or export-and-reimport affordance.** History only grows (§8), and the screen offers no control that would imply otherwise.
