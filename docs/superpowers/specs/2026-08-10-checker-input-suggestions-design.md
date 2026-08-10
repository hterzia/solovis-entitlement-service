# Checker page: input suggestions and legible lookup modes

Date: 2026-08-10
Status: approved, not yet implemented
Surface: `management/frontend/management-ui/src/routes/checker/CheckerRoute.tsx`

## Problem

The checker is the screen an operator reaches for when they need an answer about one
account and one capability. It presents three free-text inputs — Account, Capability,
Override reference — and offers no help filling any of them.

Two distinct problems follow.

**Nothing suggests the valid values.** Capability keys (`reports.monthly`) and account
external ids (`acct_9931`) are not guessable and are tedious to type correctly. An
operator who mistypes gets `No such capability`, which is a correct answer to the
question asked and no help at all with the question intended.

**The two lookup modes are invisible.** The screen supports either an account *and* a
capability, or an override reference on its own. `CheckerRoute.tsx:77-83` already
enforces this by disabling fields, but nothing states the rule — three inputs sit in a
flat list and the operator infers the constraint by watching a field grey out.

## Landscape: this screen has three claimants

Checked against 002 and 003 on 2026-08-10. The checker is the most contended screen in
the product, and two other specced features are queued to add controls to it:

| Feature | What it adds to screen 4 | State |
|---|---|---|
| **002** time-bound overrides | An optional *as at* date, a persistent banner naming the past date being shown, and `<TraceView>` entries dimmed for overrides not in force | Phase 6 (UI) **not started**; the branch is 112 commits behind main and unrebased |
| **003** plain-English checker | An ask box that turns a typed sentence into the account/capability/date triple | Service side built; the ask box is **remaining work** (spec §13) |

002's own plan already flags the collision (`plan.md:289`):

> **Interaction to settle before Phase 6 starts.** Spec 003 (plain-English checker)
> also changes screen 4 [...] Two features adding controls to the same screen should
> agree on the layout once rather than merge twice.

This design makes it three. That governs what it does and does not attempt, below.

**The suggestions are wanted downstream, not merely compatible.** 003's spec §2 already
describes the ask box as sitting *"alongside the existing account and capability
pickers"* — it calls them pickers, which today they are not. Adding the datalists makes
that sentence true rather than aspirational, and it is per-field work that neither an
added date nor an added ask box disturbs.

## What this does not change

The disabling logic stays exactly as written, including its asymmetry: Account and
Capability disable once an Override reference is present, but Override reference
disables only on Capability, never on Account. That asymmetry is deliberate and tested
— `CheckerRoute.test.tsx:43` types an Account and *then* an Override reference, and
`handleSubmit` lets the override win, because an override reference resolves to its own
account and any account typed alongside it is a redundant second constraint the
operator may not even know.

An earlier reading of this as a bug was wrong. Nothing in this design touches
behaviour; all three changes are additive or presentational.

## Design

### 1. Capability suggestions, from data already on the page

Add `list="checker-capabilities"` to the Capability input and a sibling `<datalist>`
populated from `capabilitiesQuery` — the query `CheckerRoute.tsx:40-43` already runs to
resolve tier display names. No new request.

Each option carries the key as its value and the display name as its text:

```
<option value={c.key}>{c.displayName}</option>
```

so the operator reads the human name and the field receives the key.

`listCapabilities()` defaults to `status=ACTIVE` (`CapabilityAdminController.java:31`),
so retired capabilities are not suggested. They remain *typeable* — a `<datalist>`
constrains nothing — which preserves the retired-capability error path and the test at
`CheckerRoute.test.tsx:151`.

### 2. Account suggestions, one new query

Add `list="checker-accounts"` to the Account input, backed by a `useQuery` mirroring
`AccountsListRoute.tsx:15-16`:

- `queryFn: () => listAccounts({ q: account || undefined })`
- `enabled: account !== ''`, so an untouched form and the override-reference flow fetch
  nothing

**It must not reuse `queryKeys.accounts({ q })`.** `AccountsListRoute` holds that key
with a `useInfiniteQuery`, whose cached data is `{ pages, pageParams }` — a different
shape from the `{ accounts, nextCursor }` a plain `useQuery` expects. Sharing the key
would hand one observer the other's shape. Add a distinct factory to
`queries/keys.ts`:

```
accountSuggestions: (q: string) => ['accounts', 'suggest', q] as const
```

It still sits under the `['accounts']` prefix, so the `invalidateQueries({ queryKey:
['accounts'] })` that `AccountsListRoute.tsx:26` fires after creating an account
refreshes the suggestions too, which is the behaviour we want.

Options insert the external id and are labelled the way the accounts list already
labels an account (`AccountsListRoute.tsx:38`), which handles the nullable name:

```
<option value={a.account}>{a.name ? `${a.name} (${a.account})` : a.account}</option>
```

Note the field is `AccountSummary.account`, not `externalId` — `account` *is* the
external id, and it is the value `checkDecision` sends. `name` is `string | null`, so
the fallback above is required, not cosmetic.

**No debounce.** `AccountsListRoute` — the screen that already does exactly this search
— fires on every keystroke and lets TanStack Query's cache absorb the repeats. Matching
it keeps the codebase free of its first debounce abstraction. The supported client base
is 300 accounts (spec §7) against a page size of 50, on a LAN.

### 3. Lookup modes made legible — deferred, deliberately

The approved design wrapped each lookup mode in a `<fieldset>`/`<legend>` to state the
exclusivity the form currently only enforces. **That is now held back and folded into
the single layout settlement 002's plan calls for.**

The problem is real and this design does not withdraw it — an operator still infers the
rule by watching a field grey out. But "two lookup modes" is the wrong frame to carve
into the markup right now, and it is about to stop being true twice over:

- 002 adds an *as at* date that is **orthogonal** to the mode, not a third mode. It
  applies to both. A two-fieldset split invites it into one of them, where it does not
  belong.
- 003 adds a sentence that produces the same account/capability/date triple — a third
  *entry path* to the same lookup, not a third mode.

Shipping the grouping now means restructuring one small form in three consecutive
merges, each author reading the previous author's structure as intent. The settlement
002 already schedules should decide the whole shape once, with all three sets of
controls known.

**Rejected, and worth recording for that settlement: radio buttons.** Radios force one
mode to be selected before its fields accept input, which makes the tested "type an
account, then paste an override reference" path impossible to perform. Any future
layout has the same constraint to respect.

**Rejected: a real combobox component.** A headless-library or hand-rolled combobox
would add a dependency, a component, and a keyboard-and-ARIA surface to get right. A
`<datalist>` keeps each field a plain text input, so every `getByLabelText` and
`user.type` in the existing suite continues to work unchanged — and stays trivial to
re-home when the layout is settled.

## Testing

**`CheckerRoute.test.tsx` is not edited at all** — not its cases, not its setup. That is
the check on whether this stayed presentational, and it is a stronger guarantee than an
earlier draft of this spec claimed: that draft called for adding an MSW handler for
`GET /accounts`, but `handlers.ts:183` already serves one, complete with the `q`
filtering this design relies on, and the checker suite already uses the shared server.
No new handler is needed. Changing an assertion means the change was not presentational
after all, and is grounds to stop and re-open the design.

`src/test/setup.ts:10` sets `onUnhandledRequest: 'error'`, so if the new query is ever
pointed at a path the shared handlers do not cover, the suite fails loudly rather than
silently returning nothing.

New coverage, in a new file or an appended `describe`:

- The capability datalist offers active capabilities by key, labelled by display name.
- A retired capability is absent from the suggestions but still accepted when typed.
- Typing an account queries `/accounts?q=` and offers the results.
- An empty Account field issues no accounts request.

**How to assert on a `<datalist>`.** The codebase has no precedent, and this is the one
place the implementer would otherwise guess. Do not rely on `getByRole('option')`: ARIA
maps `option` through a listbox context, and a `<datalist>`'s options are not reliably
exposed in jsdom. Query the list by id and read its options directly:

```
const options = document.getElementById('checker-capabilities')!.querySelectorAll('option')
```

then assert over their `value` and `textContent`. If that proves awkward, the fallback
is to assert the input carries the right `list` attribute and test the option set
through the query layer instead — but do not weaken a test to "the datalist exists."

**One e2e test, in the `Screen 4 — checker` block of `e2e/operator-screens.spec.ts`.**
Component tests here are MSW-backed and so prove only that the component renders a list
the *mock* returned. The claim actually being made is that the capability datalist is
populated from the live registry and the account datalist from a live `q` search — SPA
and service agreeing — and that is exactly the class of thing only e2e catches. Assert
against the seeded fixtures that both lists carry real options.

Mind the suite's own trap: it runs serially against one shared service on 8099, and
capability keys overlap as substrings (`e2e.probe.switch` contains "pro"), so scope the
option assertions exactly rather than by `toContainText`.

## Styling

With the `<fieldset>` work deferred, this design adds **no new markup structure** — two
`list` attributes and two `<datalist>` elements, which render no visible chrome of their
own. The suggestion dropdown is drawn by the browser and is not stylable, so there is no
`tokens.css` surface here and no `solovis-designer` pass is required. If the
implementation finds itself reaching for a colour or spacing value, that is the signal
it has strayed into the deferred layout work.

## Compatibility with 002 and 003

Constraints inherited from 002's plan that this work must not violate:

- **`TraceCandidate.outcome` stays typed `string`** in `types/domain.ts`. 002 adds
  `NOT_IN_FORCE_*` outcomes and relies on new outcomes needing new *labels*, not a type
  change. Nothing here touches it; do not "tighten" it to a union on the way past.
- **The SPA renders, it does not derive.** When 002's dates arrive, standing is computed
  in core and carried on the wire. The same rule governs this work: the suggestion lists
  are the service's answer to "which capabilities are active" and "which accounts match
  this text", never a client-side filter over a fuller list.

## Out of scope

Suggestions for the Override reference field. It is an opaque generated id with nothing
to enumerate, and it arrives on the clipboard from another screen.

The `<fieldset>` grouping of the lookup modes — deferred to 002's layout settlement, for
the reasons in §3.
