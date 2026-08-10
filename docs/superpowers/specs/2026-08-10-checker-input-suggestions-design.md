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

A `useQuery` mirroring `AccountsListRoute.tsx:15-16`:

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

Options show the account name and insert the external id:

```
<option value={a.externalId}>{a.name}</option>
```

**No debounce.** `AccountsListRoute` — the screen that already does exactly this search
— fires on every keystroke and lets TanStack Query's cache absorb the repeats. Matching
it keeps the codebase free of its first debounce abstraction. The supported client base
is 300 accounts (spec §7) against a page size of 50, on a LAN.

### 3. Lookup modes made legible

Wrap each mode in a `<fieldset>` with a `<legend>`:

- *Look up by account and capability* — Account, Capability
- *Or by override reference* — Override reference

Presentation only. No state, no handler, no disabling rule changes.

**Rejected: radio buttons.** Radios would express the exclusivity more forcefully, but
they force one mode to be selected before its fields accept input, which makes the
tested "type an account, then paste an override reference" path impossible to perform.
That would mean rewriting passing tests to suit new chrome. The `<fieldset>`/`<legend>`
grouping states the same rule and leaves behaviour untouched.

**Rejected: a real combobox component.** A headless-library or hand-rolled combobox
would add a dependency, a component, and a keyboard-and-ARIA surface to get right. A
`<datalist>` keeps each field a plain text input, so every `getByLabelText` and
`user.type` in the existing suite continues to work unchanged.

## Testing

Every existing *test case* must pass with its body unchanged — that suite is the check
on whether this stayed presentational. The only permitted edit to
`CheckerRoute.test.tsx` is to its shared setup, which needs an MSW handler for `GET
/accounts`; without one the new query hits an unhandled request. Changing an assertion
means the change was not presentational after all, and is grounds to stop and re-open
the design.

New coverage:

- The capability datalist offers active capabilities by key, labelled by display name.
- A retired capability is absent from the suggestions but still accepted when typed.
- Typing an account queries `/accounts?q=` and offers the results.
- An empty Account field issues no accounts request.

## Styling

The `<fieldset>`/`<legend>` markup goes through the `solovis-designer` agent so its
spacing, border and type come from `tokens.css` custom properties and `.sv-*` classes.
No new colour, radius, spacing or type value.

## Out of scope

Suggestions for the Override reference field. It is an opaque generated id with nothing
to enumerate, and it arrives on the clipboard from another screen.
