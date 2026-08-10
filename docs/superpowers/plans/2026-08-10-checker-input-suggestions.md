# Checker Input Suggestions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the checker's Account and Capability fields native suggestion dropdowns, so an operator no longer has to already know an unguessable key or id by heart.

**Architecture:** Two `<datalist>` elements, each attached to the existing text input by a `list` attribute. The capability list reuses the query the page already runs for tier names, so it costs no request. The account list adds one `useQuery` mirroring the accounts screen's own search. Nothing about the form's behaviour, state or disabling rules changes — a `<datalist>` constrains nothing and each field stays a plain text input.

**Tech Stack:** React 19, TanStack Query v5, TypeScript, Vitest + Testing Library + MSW, Playwright.

**Source spec:** `docs/superpowers/specs/2026-08-10-checker-input-suggestions-design.md`

> **Note on the spec's line references.** The spec was written before 002's checker UI merged (commit `1a3e18f`, 2026-08-10 08:37). Its citations of `CheckerRoute.tsx:77-83` and `:40-43` are stale; this plan carries the current line numbers. Every *substantive* claim in the spec was re-verified against the post-002 code and still holds: the disabling asymmetry, the `status=ACTIVE` default, the `AccountSummary.account` field name, and the pre-existing MSW `/accounts` handler.

## Global Constraints

- **Work from `management/frontend/management-ui`.** All commands below assume that directory.
- **`src/routes/checker/CheckerRoute.test.tsx` must not be edited** — not a case, not the setup. It is the check that this change stayed presentational. 002 set the precedent by putting its own checker tests in a separate `src/routes/WindowsUi.test.tsx`; new tests here go in a new file for the same reason.
- **No new colour, radius, spacing or type value.** `.claude/design/solovis/tokens.css` is the authoritative design source. This change should need nothing from it — a `<datalist>` renders no chrome the page controls. Reaching for a style value means you have strayed into the deferred layout work (spec §3).
- **The SPA renders; it never derives.** The suggestion lists are the service's answer to "which capabilities are active" and "which accounts match this text". Never filter a fuller list client-side.
- **Do not tighten `TraceCandidate.outcome`** in `src/types/domain.ts`. It is typed `string` on purpose so 002's `NOT_IN_FORCE_*` outcomes need new labels, not a type change.
- **The `<fieldset>` grouping of lookup modes is out of scope** — deferred to 002's layout settlement (spec §3). Do not add it.
- Verification commands: `npm run test`, `npm run lint`, `npx tsc -b`, `npm run test:e2e`.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/routes/checker/CheckerRoute.tsx` | Modify | Add two `list` attributes, two `<datalist>` elements, one query |
| `src/queries/keys.ts` | Modify | Add the `accountSuggestions` key factory |
| `src/routes/checker/CheckerSuggestions.test.tsx` | Create | All new component tests for this feature |
| `e2e/operator-screens.spec.ts` | Modify | One test in the existing `Screen 4 — checker` block |

---

### Task 1: Capability suggestions

The capability list is already in memory — `CheckerRoute.tsx:47-50` fetches it to resolve tier display names. This task spends no new request.

**Files:**
- Modify: `src/routes/checker/CheckerRoute.tsx:87-89` (the Capability `<label>`)
- Test: `src/routes/checker/CheckerSuggestions.test.tsx` (create)

**Interfaces:**
- Consumes: `capabilitiesQuery` — the existing `useQuery` at `CheckerRoute.tsx:47-50`, whose data is `{ capabilities: Capability[]; snapshotVersion: number }`. `Capability` has `key: string`, `displayName: string`, `status: 'ACTIVE' | 'RETIRED'`.
- Produces: a `<datalist id="checker-capabilities">` in the document whenever `CheckerRoute` is mounted.

- [ ] **Step 1: Write the failing test**

Create `src/routes/checker/CheckerSuggestions.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { db } from '../../test/mocks/handlers'
import { CheckerRoute } from './CheckerRoute'

/**
 * Read a <datalist>'s options directly rather than through getByRole('option'): ARIA maps the
 * `option` role through a listbox context, and a <datalist>'s options are not reliably exposed
 * in jsdom. The element is found by id because that id is the contract the `list` attribute names.
 */
function optionsOf(id: string): { value: string; label: string | null }[] {
  const list = document.getElementById(id)
  if (!list) throw new Error(`No <datalist id="${id}"> in the document`)
  return Array.from(list.querySelectorAll('option')).map((o) => ({
    value: o.value,
    label: o.textContent,
  }))
}

describe('CheckerRoute suggestions', () => {
  it('suggests every active capability by key, labelled by its display name', async () => {
    renderWithProviders(<CheckerRoute />)

    await waitFor(() => expect(optionsOf('checker-capabilities').length).toBeGreaterThan(0))

    // The operator reads the human name; the field receives the key.
    expect(optionsOf('checker-capabilities')).toEqual(
      expect.arrayContaining([
        { value: 'reports.monthly', label: 'Monthly reports' },
        { value: 'export.parquet', label: 'Parquet export' },
        { value: 'support', label: 'Support level' },
      ]),
    )
  })

  it('omits a retired capability from the suggestions but still accepts it typed', async () => {
    db.capabilities.push({
      key: 'legacy.export',
      area: 'legacy',
      displayName: 'Legacy export',
      description: null,
      valueType: 'SWITCH',
      default: { type: 'SWITCH', enabled: false },
      offValue: null,
      tiers: [],
      status: 'RETIRED',
    })
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)

    await waitFor(() => expect(optionsOf('checker-capabilities').length).toBeGreaterThan(0))
    expect(optionsOf('checker-capabilities').map((o) => o.value)).not.toContain('legacy.export')

    // Suggesting is not constraining: the retired-capability error path (c19) must stay reachable.
    await user.type(screen.getByLabelText('Capability'), 'legacy.export')
    expect(screen.getByLabelText('Capability')).toHaveValue('legacy.export')
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test -- src/routes/checker/CheckerSuggestions.test.tsx`

Expected: FAIL — both tests throw `No <datalist id="checker-capabilities"> in the document`.

- [ ] **Step 3: Add the datalist**

In `src/routes/checker/CheckerRoute.tsx`, replace the Capability label (currently lines 87-89):

```tsx
        <label className="sv-label">Capability
          <input className="sv-field" aria-label="Capability" value={capability} disabled={overrideRef !== ''} onChange={(e) => setCapability(e.target.value)} />
        </label>
```

with:

```tsx
        <label className="sv-label">Capability
          <input list="checker-capabilities" className="sv-field" aria-label="Capability" value={capability} disabled={overrideRef !== ''} onChange={(e) => setCapability(e.target.value)} />
        </label>
        {/* Suggestions only — a <datalist> constrains nothing, so a retired key stays typeable and
            its §6.3 error stays reachable. The list is the service's answer to "what is active",
            never a client-side filter over a fuller one. */}
        <datalist id="checker-capabilities">
          {(capabilitiesQuery.data?.capabilities ?? []).map((c) => (
            <option key={c.key} value={c.key}>{c.displayName}</option>
          ))}
        </datalist>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test -- src/routes/checker/CheckerSuggestions.test.tsx`

Expected: PASS, 2 tests.

- [ ] **Step 5: Verify nothing else moved**

Run: `npm run test -- src/routes/checker/CheckerRoute.test.tsx`

Expected: PASS, all existing tests, with the file unedited. If any fails, stop — the change was not presentational and the design needs re-opening.

- [ ] **Step 6: Commit**

```bash
git add src/routes/checker/CheckerRoute.tsx src/routes/checker/CheckerSuggestions.test.tsx
git commit -m "feat(checker): suggest active capabilities in the capability field

The list is already in memory for tier names, so this costs no request.
Suggesting is not constraining: a retired key stays typeable and its
§6.3 error stays reachable."
```

---

### Task 2: Account suggestions

**Files:**
- Modify: `src/queries/keys.ts:7-8` (add one factory)
- Modify: `src/routes/checker/CheckerRoute.tsx` — add an import, a query, a `list` attribute, a `<datalist>`
- Test: `src/routes/checker/CheckerSuggestions.test.tsx` (append to the file created in Task 1)

**Interfaces:**
- Consumes: `listAccounts(params?: { q?: string; planKey?: string; cursor?: string })` from `src/api/accounts.ts`, returning `Promise<{ accounts: AccountSummary[]; nextCursor: string | null }>`. `AccountSummary` is `{ account: string; name: string | null; planKey: string; status: 'ACTIVE' | 'CLOSED' }` — note the external id lives on `account`, **not** `externalId`, and `name` is nullable.
- Produces: `queryKeys.accountSuggestions(q: string) => readonly ['accounts', 'suggest', string]`, and a `<datalist id="checker-accounts">`.

- [ ] **Step 1: Write the failing tests**

Append to `src/routes/checker/CheckerSuggestions.test.tsx`. Add `http`, `HttpResponse` and `server` to the imports at the top of the file:

```tsx
import { http, HttpResponse } from 'msw'
import { server } from '../../test/mocks/server'
```

Then add these two tests inside the existing `describe` block:

```tsx
  it('suggests accounts matching what has been typed, labelled by name', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)

    await user.type(screen.getByLabelText('Account'), 'north')

    // The seeded account is Northwind Capital / acct_9931. The label follows the accounts
    // screen's own convention so a nameless account still reads as something.
    await waitFor(() =>
      expect(optionsOf('checker-accounts')).toEqual([
        { value: 'acct_9931', label: 'Northwind Capital (acct_9931)' },
      ]),
    )
  })

  it('asks the service for no accounts until something is typed', async () => {
    let calls = 0
    server.use(
      http.get('/admin/v1/accounts', () => {
        calls += 1
        return HttpResponse.json({ accounts: [], nextCursor: null })
      }),
    )
    renderWithProviders(<CheckerRoute />)

    // Wait for the page to have settled on a query that *should* run, so "no call yet" means
    // "never called" rather than "not called during the first tick".
    await waitFor(() => expect(optionsOf('checker-capabilities').length).toBeGreaterThan(0))

    expect(calls).toBe(0)
  })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test -- src/routes/checker/CheckerSuggestions.test.tsx`

Expected: FAIL — the first throws `No <datalist id="checker-accounts"> in the document`. The second passes vacuously for now; it becomes a real guard in Step 5.

- [ ] **Step 3: Add the query key factory**

In `src/queries/keys.ts`, add this line immediately after the `accounts:` entry (line 7):

```ts
  /**
   * Deliberately not `accounts({ q })`: the accounts screen holds that key with a
   * `useInfiniteQuery`, whose cached shape is `{ pages, pageParams }` rather than the
   * `{ accounts, nextCursor }` a plain `useQuery` expects. Sharing it would hand one observer
   * the other's shape. Still under the `['accounts']` prefix, so creating an account
   * invalidates these suggestions too.
   */
  accountSuggestions: (q: string) => ['accounts', 'suggest', q] as const,
```

- [ ] **Step 4: Add the query and the datalist**

In `src/routes/checker/CheckerRoute.tsx`, add the import beside the existing `listCapabilities` import (line 4):

```tsx
import { listAccounts } from '../../api/accounts'
```

Add this query immediately after `capabilitiesQuery` (after line 50):

```tsx
  // Undebounced on purpose: `AccountsListRoute` — the screen that already runs this exact search
  // — fires on every keystroke and lets the query cache absorb the repeats. Matching it keeps the
  // codebase free of its first debounce abstraction.
  const accountSuggestionsQuery = useQuery({
    queryKey: queryKeys.accountSuggestions(account),
    queryFn: () => listAccounts({ q: account }),
    enabled: account !== '',
  })
```

Replace the Account label (currently lines 84-86):

```tsx
        <label className="sv-label">Account
          <input className="sv-field" aria-label="Account" value={account} disabled={overrideRef !== ''} onChange={(e) => setAccount(e.target.value)} />
        </label>
```

with:

```tsx
        <label className="sv-label">Account
          <input list="checker-accounts" className="sv-field" aria-label="Account" value={account} disabled={overrideRef !== ''} onChange={(e) => setAccount(e.target.value)} />
        </label>
        <datalist id="checker-accounts">
          {(accountSuggestionsQuery.data?.accounts ?? []).map((a) => (
            <option key={a.account} value={a.account}>
              {a.name ? `${a.name} (${a.account})` : a.account}
            </option>
          ))}
        </datalist>
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm run test -- src/routes/checker/CheckerSuggestions.test.tsx`

Expected: PASS, 4 tests.

- [ ] **Step 6: Verify nothing else moved**

Run: `npm run test`

Expected: PASS, the whole frontend suite, with `CheckerRoute.test.tsx` unedited.

Run: `npx tsc -b && npm run lint`

Expected: both clean. `tsc` matters here — `npm run test` alone does not typecheck, and the deployed container is built by `npm run build`, which runs `tsc -b`.

- [ ] **Step 7: Commit**

```bash
git add src/queries/keys.ts src/routes/checker/CheckerRoute.tsx src/routes/checker/CheckerSuggestions.test.tsx
git commit -m "feat(checker): suggest accounts as the operator types

Mirrors the accounts screen's own search, undebounced, with its own query
key: that screen holds accounts({q}) with a useInfiniteQuery whose cached
shape would collide with a plain useQuery's."
```

---

### Task 3: Prove it against the real service

Component tests are MSW-backed, so they prove only that the component renders a list the *mock* returned. The claim actually being made is that these lists are filled from the live registry and a live search — SPA and service agreeing — which is exactly the class of thing only e2e catches.

**Files:**
- Modify: `management/frontend/management-ui/e2e/operator-screens.spec.ts` — one test inside the existing `test.describe('Screen 4 — checker', ...)` block (opens at line 164)

**Interfaces:**
- Consumes: the `#checker-capabilities` and `#checker-accounts` datalist ids produced by Tasks 1 and 2, and the seeded backend fixtures (`acct_9931`, `reports.monthly`) that the block's existing tests already rely on.

- [ ] **Step 1: Write the failing test**

Add inside the `Screen 4 — checker` describe block:

```ts
  test('the capability and account fields suggest what the service actually has', async ({ page }) => {
    await page.goto('/checker')

    // <option>s inside a <datalist> are never visible, so read their values rather than
    // asserting visibility. Exact array membership, not substring: capability keys overlap
    // as substrings and this suite shares one service.
    const capabilityValues = () =>
      page.locator('#checker-capabilities option')
        .evaluateAll((els) => els.map((e) => (e as HTMLOptionElement).value))

    await expect.poll(capabilityValues).toContain('reports.monthly')

    const accountValues = () =>
      page.locator('#checker-accounts option')
        .evaluateAll((els) => els.map((e) => (e as HTMLOptionElement).value))

    // Empty until asked, then filled from a live `q` search.
    expect(await accountValues()).toEqual([])
    await page.getByLabel('Account').fill('acct_9931')
    await expect.poll(accountValues).toContain('acct_9931')
  })
```

- [ ] **Step 2: Prove the test bites**

This test cannot fail-first in the usual way — Tasks 1 and 2 have already shipped the thing it checks. So prove it is wired to something real rather than passing vacuously.

Temporarily change `#checker-capabilities` to `#checker-capabilities-wrong` in the test, then run:

`npm run test:e2e -- --grep "suggest what the service actually has"`

Expected: FAIL — `expect.poll` times out having only ever seen `[]`. Change the id back and re-run; expected: PASS. A test that passes both ways is asserting nothing.

> Before believing an e2e failure here, check nothing else is on port 8099. `start-backend.sh` wipes the DB on launch, and a leftover JVM from a previous run serves a dirty one. `ss -tlnp | grep 8099`.

- [ ] **Step 3: Run the whole e2e suite**

Run: `npm run test:e2e`

Expected: PASS, all tests. The suite runs serially against one shared service; this test writes nothing, so it cannot disturb its neighbours.

- [ ] **Step 4: Commit**

```bash
git add e2e/operator-screens.spec.ts
git commit -m "test(e2e): prove the checker datalists are filled by the real service

MSW can only prove the component renders what the mock returned. That the
capability list comes from the live registry, and the account list from a
live q search, is SPA/service agreement and only e2e sees it."
```

---

## Verification

After all three tasks, from `management/frontend/management-ui`:

```bash
npm run test          # expect the full unit suite green, CheckerRoute.test.tsx unedited
npx tsc -b            # expect clean — npm run test does not typecheck
npm run lint          # expect clean
npm run test:e2e      # expect green, including the new Screen 4 test
git status --short    # expect only the four files this plan names
```

`git status` matters: this repo carries long-lived unrelated pending changes under `.specs/**` and `DECISIONS.md`, and work is split across worktrees. **Never `git add -A`** — every commit above stages named files only.

## Out of scope

- The `<fieldset>` grouping of the two lookup modes — deferred to 002's layout settlement (spec §3), which is now the only unsettled claimant on this screen alongside 003's ask box.
- Suggestions for the Override reference field. It is an opaque generated id with nothing to enumerate.
