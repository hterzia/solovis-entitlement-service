# Entitlement Operator UI Contract Fixes Implementation Plan

> **Status: complete and merged.** All five tasks landed — `AccountSummary.account` + `status`, `createAccount({externalId})` with the narrowed response, `CapabilityRetireResult`'s nested shape, the remaining response-shape fixes, and `staticData.requiredRole` on all nine routes. The `- [ ]` checkboxes were never ticked back — this file is an archived record, not outstanding work. Verify against the code, not the boxes.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix five places where the operator UI (`management/frontend/management-ui`) was built against MSW mocks that don't match the real, now-merged `entitlement-service` backend, plus add the `requiredRole` route metadata `ui-screens.md` calls for. Two of these (account-list navigation, account creation) are outright broken against the real service; the rest are type lies with no crash today but wrong contracts.

**Architecture:** Every fix is confined to `src/api/*.ts` (request/response shapes), the one or two routes that read a changed shape, and `src/test/mocks/handlers.ts` (which must now mirror the real backend's actual wire shapes instead of the frontend's original, incorrect assumptions). No new dependencies, no new screens.

**Tech Stack:** React 19 + TypeScript 6, Vite 8, TanStack Router 1.170 + TanStack Query 5, Vitest 4 + Testing Library + MSW 2.

## Global Constraints

- Module: `management/frontend/management-ui`. Run tests with `npm test` (vitest), typecheck with `npx tsc -b --force`, lint with `npm run lint`.
- `.claude/design/solovis/tokens.css` governs all visual output — none of these fixes touch styling, so this should not come up, but do not introduce new class names or literal colour/spacing values regardless.
- Every mutation's success confirmation must read the liveness promise from a real number in the response or from `GET /admin/v1/meta` — never a hard-coded literal (c41). None of these fixes remove an existing liveness confirmation; two of them (Task 4) let a confirmation read its number from the mutation's own response instead of a `/meta` fallback, which is strictly more accurate, not a regression.
- MSW `handlers.ts` and `fixtures.ts` are the test-time stand-in for the real backend. Every shape change in this plan must land in both the TypeScript type **and** the corresponding mock handler in the same task — a handler left stale defeats the point of this whole plan (it is exactly how the original mismatches went undetected).
- `db.createdAccounts` (in `handlers.ts`) stays typed `AccountDetail[]` — it backs `GET /accounts/{external}` and other full-detail lookups for accounts created mid-test. Only the **response body** of `POST /admin/v1/accounts` narrows to the smaller real shape; what's stored in `db.createdAccounts` does not.
- Do not rename the TanStack Router path param `external` (the route is `/accounts/$external` and stays that way) — only the `AccountSummary` **data field** (`external` → `account`) is wrong and being fixed. Keep these two conceptually distinct while editing `AccountsListRoute.tsx`.

---

### Task 1: Fix `AccountSummary`'s field name (`external` → `account`)

The real `AccountSummaryDto` (backend) is `{account, name, planKey, status}`. The frontend's `AccountSummary` type says `{external, name, planKey}` — a field that does not exist on the wire. `AccountsListRoute.tsx` uses `a.external` for the React key, the link's route param, and the display text, so against the real backend every row is `undefined` and every link points at `/accounts/undefined`. This is the most severe bug in the batch: it breaks the only path from the accounts list into an account's detail page.

**Files:**
- Modify: `management/frontend/management-ui/src/types/domain.ts`
- Modify: `management/frontend/management-ui/src/routes/accounts/AccountsListRoute.tsx`
- Modify: `management/frontend/management-ui/src/routes/accounts/AccountsListRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/test/mocks/handlers.ts`

**Interfaces:**
- Produces: `AccountSummary` now has `account: string` (not `external`) and an added `status: 'ACTIVE' | 'CLOSED'` field, matching `AccountDetail.status`'s existing union.

- [ ] **Step 1: Write the failing test**

In `AccountsListRoute.test.tsx`, the `'loads the next page via cursor when more accounts exist'` test's `server.use()` override currently reads:

```ts
    server.use(
      http.get('/admin/v1/accounts', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        return cursor
          ? HttpResponse.json({ accounts: [{ external: 'acct_page_two', name: null, planKey: 'pro' }], nextCursor: null })
          : HttpResponse.json({ accounts: [{ external: 'acct_page_one', name: null, planKey: 'pro' }], nextCursor: 'acct_next_page' })
      }),
    )
```

Change it to:

```ts
    server.use(
      http.get('/admin/v1/accounts', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        return cursor
          ? HttpResponse.json({ accounts: [{ account: 'acct_page_two', name: null, planKey: 'pro' }], nextCursor: null })
          : HttpResponse.json({ accounts: [{ account: 'acct_page_one', name: null, planKey: 'pro' }], nextCursor: 'acct_next_page' })
      }),
    )
```

And the `'resets to the first page when the search term changes'` test's `server.use()` override currently reads:

```ts
    server.use(
      http.get('/admin/v1/accounts', ({ request }) => {
        const url = new URL(request.url)
        const cursor = url.searchParams.get('cursor')
        if (url.searchParams.get('q')) {
          return HttpResponse.json({ accounts: [{ external: 'acct_searched', name: null, planKey: 'pro' }], nextCursor: null })
        }
        return cursor
          ? HttpResponse.json({ accounts: [{ external: 'acct_page_two', name: null, planKey: 'pro' }], nextCursor: null })
          : HttpResponse.json({ accounts: [{ external: 'acct_page_one', name: null, planKey: 'pro' }], nextCursor: 'acct_next_page' })
      }),
    )
```

Change it to:

```ts
    server.use(
      http.get('/admin/v1/accounts', ({ request }) => {
        const url = new URL(request.url)
        const cursor = url.searchParams.get('cursor')
        if (url.searchParams.get('q')) {
          return HttpResponse.json({ accounts: [{ account: 'acct_searched', name: null, planKey: 'pro' }], nextCursor: null })
        }
        return cursor
          ? HttpResponse.json({ accounts: [{ account: 'acct_page_two', name: null, planKey: 'pro' }], nextCursor: null })
          : HttpResponse.json({ accounts: [{ account: 'acct_page_one', name: null, planKey: 'pro' }], nextCursor: 'acct_next_page' })
      }),
    )
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run src/routes/accounts/AccountsListRoute.test.tsx`
Expected: `tsc` catches nothing yet (the type still says `external`), but the existing `'lists accounts and links to the account detail route'` test — which uses the *default* handler, still returning `external` — will still pass at this point since nothing downstream has changed yet. This step exists to confirm the file parses; the real failure shows up once Step 3 changes the type.

- [ ] **Step 3: Fix the type**

In `types/domain.ts`, replace:

```ts
export interface AccountSummary {
  external: string
  name: string | null
  planKey: string
}
```

with:

```ts
export interface AccountSummary {
  account: string
  name: string | null
  planKey: string
  status: 'ACTIVE' | 'CLOSED'
}
```

- [ ] **Step 4: Run `tsc` to see every now-broken call site**

Run: `npx tsc -b --force`
Expected: errors in `AccountsListRoute.tsx` (`a.external` doesn't exist) and `test/mocks/handlers.ts` (the GET `/admin/v1/accounts` handler's `.map` still produces `{external, name, planKey}`).

- [ ] **Step 5: Fix `AccountsListRoute.tsx`**

Replace:

```tsx
        {query.data?.accounts.map((a) => (
          <li key={a.external}>
            <Link to="/accounts/$external" params={{ external: a.external }} className="sv-link">
              {a.name ? `${a.name} (${a.external})` : a.external}
            </Link>
          </li>
        ))}
```

with:

```tsx
        {query.data?.accounts.map((a) => (
          <li key={a.account}>
            <Link to="/accounts/$external" params={{ external: a.account }} className="sv-link">
              {a.name ? `${a.name} (${a.account})` : a.account}
            </Link>
          </li>
        ))}
```

(Note: the route param name on the left of `params={{ external: ... }}` stays `external` — that's the URL segment name from `/accounts/$external`, unrelated to the `AccountSummary` field. Only the right-hand `a.external` → `a.account` reads change.)

- [ ] **Step 6: Fix the MSW handler**

In `handlers.ts`, replace:

```ts
  http.get('/admin/v1/accounts', ({ request }) => {
    const url = new URL(request.url)
    const q = url.searchParams.get('q')?.toLowerCase()
    const all = [db.account, ...db.createdAccounts].map((a) => ({ external: a.account, name: a.name, planKey: a.plan.key }))
    const filtered = q ? all.filter((a) => a.external.toLowerCase().includes(q) || a.name?.toLowerCase().includes(q)) : all
    return HttpResponse.json({ accounts: filtered, nextCursor: null })
  }),
```

with:

```ts
  http.get('/admin/v1/accounts', ({ request }) => {
    const url = new URL(request.url)
    const q = url.searchParams.get('q')?.toLowerCase()
    const all = [db.account, ...db.createdAccounts].map((a) => ({ account: a.account, name: a.name, planKey: a.plan.key, status: a.status }))
    const filtered = q ? all.filter((a) => a.account.toLowerCase().includes(q) || a.name?.toLowerCase().includes(q)) : all
    return HttpResponse.json({ accounts: filtered, nextCursor: null })
  }),
```

- [ ] **Step 7: Run `tsc` and the full test suite**

Run: `npx tsc -b --force` — expected clean.
Run: `npm test` — expected all green, including `AccountsListRoute.test.tsx`'s four tests.

- [ ] **Step 8: Commit**

```bash
git add management/frontend/management-ui/src/types/domain.ts \
        management/frontend/management-ui/src/routes/accounts/AccountsListRoute.tsx \
        management/frontend/management-ui/src/routes/accounts/AccountsListRoute.test.tsx \
        management/frontend/management-ui/src/test/mocks/handlers.ts
git commit -m "fix(management-ui): AccountSummary's field is 'account', not 'external'"
```

---

### Task 2: Fix account creation (`externalId`, and a narrower response)

`createAccount` posts `{external, name}`; the real `AccountCreateRequest` requires `@NotBlank externalId`. Every real account creation currently fails validation. Separately, `createAccount`'s declared return type is the full `AccountDetail`, but the real `POST /admin/v1/accounts` returns only `AccountSummaryDto` (`account, name, planKey, status`) — no `plan`, `entitlements`, `overrides`, or `snapshotVersion`. `AccountsListRoute.tsx` doesn't read the creation response today (it just invalidates the list query), so narrowing the type is safe.

**Files:**
- Modify: `management/frontend/management-ui/src/api/accounts.ts`
- Modify: `management/frontend/management-ui/src/routes/accounts/AccountsListRoute.tsx`
- Modify: `management/frontend/management-ui/src/test/mocks/handlers.ts`

**Interfaces:**
- Consumes: `AccountSummary` (fixed by Task 1 — `{account, name, planKey, status}`), which is exactly the shape the real backend's `create()` and `search()` share (both return `AccountSummaryDto`).
- Produces: `createAccount(input: {externalId: string; name?: string})` now returns `AccountSummary` (was the full `AccountDetail`).

- [ ] **Step 1: Write the failing test**

`AccountsListRoute.test.tsx`'s existing `'creates a new account, assigned to the default plan'` test already exercises this path end-to-end through the real UI form and MSW; no new test is needed to catch the field-name bug (the existing test will start failing once Step 3 changes the mock to require `externalId`, if `AccountsListRoute.tsx` hasn't been updated yet — that's the point). Confirm the current (pre-fix) state: run

Run: `npx vitest run src/routes/accounts/AccountsListRoute.test.tsx -t "creates a new account"`
Expected: currently passes (both sides still say `external`) — this is the baseline before the deliberate mismatch-then-fix below.

- [ ] **Step 2: Fix `api/accounts.ts`**

Replace:

```ts
export function createAccount(input: { external: string; name?: string }) {
  return apiPost<AccountDetail>('/accounts', input)
}
```

with:

```ts
export function createAccount(input: { externalId: string; name?: string }) {
  return apiPost<AccountSummary>('/accounts', input)
}
```

(`AccountSummary` is already imported in this file for `listAccounts`'s return type.)

- [ ] **Step 3: Run `tsc` to find every broken call site**

Run: `npx tsc -b --force`
Expected: error in `AccountsListRoute.tsx` (`createAccount({ external: newExternal })` no longer matches the parameter type).

- [ ] **Step 4: Fix `AccountsListRoute.tsx`**

Replace:

```tsx
    mutationFn: () => createAccount({ external: newExternal }),
```

with:

```tsx
    mutationFn: () => createAccount({ externalId: newExternal }),
```

(The local state variable `newExternal` itself is not renamed — it's a local UI concept, not a wire field.)

- [ ] **Step 5: Fix the MSW handler to require and return the real shape**

In `handlers.ts`, replace:

```ts
  http.post('/admin/v1/accounts', async ({ request }) => {
    const body = (await request.json()) as { external: string; name?: string }
    const defaultPlan = db.plans.find((p) => p.isDefaultForNewAccounts)
    if (!defaultPlan) return problem(422, 'entitlement/default-plan-required', 'No default plan is designated.')
    const created: AccountDetail = {
      account: body.external,
      name: body.name ?? null,
      status: 'ACTIVE',
      plan: { key: defaultPlan.key, name: defaultPlan.name, assignedAt: new Date(0).toISOString(), assignedBy: 'dev-operator', source: 'PERSON' },
      snapshotVersion: 48211,
      entitlements: [],
      overrides: [],
    }
    db.createdAccounts.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),
```

with:

```ts
  http.post('/admin/v1/accounts', async ({ request }) => {
    const body = (await request.json()) as { externalId: string; name?: string }
    const defaultPlan = db.plans.find((p) => p.isDefaultForNewAccounts)
    if (!defaultPlan) return problem(422, 'entitlement/default-plan-required', 'No default plan is designated.')
    const detail: AccountDetail = {
      account: body.externalId,
      name: body.name ?? null,
      status: 'ACTIVE',
      plan: { key: defaultPlan.key, name: defaultPlan.name, assignedAt: new Date(0).toISOString(), assignedBy: 'dev-operator', source: 'PERSON' },
      snapshotVersion: 48211,
      entitlements: [],
      overrides: [],
    }
    db.createdAccounts.push(detail)
    return HttpResponse.json({ account: detail.account, name: detail.name, planKey: defaultPlan.key, status: detail.status }, { status: 201 })
  }),
```

(`db.createdAccounts` keeps the full `AccountDetail` — needed by `findAccount()` for the subsequent `GET /accounts/{external}` the test performs indirectly via navigation and by `PUT .../plan` — only the HTTP response returned to the caller narrows.)

- [ ] **Step 6: Run `tsc` and the full test suite**

Run: `npx tsc -b --force` — expected clean.
Run: `npm test` — expected all green, including the `'creates a new account, assigned to the default plan'` test (it never read the creation response's shape, only that the new account's link later appears in the re-fetched list).

- [ ] **Step 7: Commit**

```bash
git add management/frontend/management-ui/src/api/accounts.ts \
        management/frontend/management-ui/src/routes/accounts/AccountsListRoute.tsx \
        management/frontend/management-ui/src/test/mocks/handlers.ts
git commit -m "fix(management-ui): account creation posts externalId and expects the real, narrower response"
```

---

### Task 3: Fix `retireCapability`'s response shape (nested `{capability, usage}`)

The real `CapabilityRetireResponseDto` is `{capability: CapabilityDescriptorDto, usage: {plans, liveOverrides}}` — nested. The frontend's `retireCapability` declares a flat `Capability & {usage}`. Nothing in `CapabilityDetailRoute.tsx` reads the retire mutation's result today (it only invalidates the capability query and closes the confirmation panel), so this is a pure type/mock correctness fix, not a behavior change — but the existing `capabilities.test.ts` test does assert the (wrong) flat shape and must be corrected alongside.

**Files:**
- Modify: `management/frontend/management-ui/src/api/capabilities.ts`
- Modify: `management/frontend/management-ui/src/api/capabilities.test.ts`
- Modify: `management/frontend/management-ui/src/test/mocks/handlers.ts`

**Interfaces:**
- Produces: `retireCapability(key)` now returns `{capability: Capability; usage: {plans: string[]; liveOverrides: number}}` (was `Capability & {usage: {...}}`).

- [ ] **Step 1: Update the test to the correct shape first (TDD against the known-correct contract)**

In `capabilities.test.ts`, replace:

```ts
  it('retires a capability and reports usage', async () => {
    const retired = await retireCapability('reports.monthly')
    expect(retired.status).toBe('RETIRED')
    expect(retired.usage.liveOverrides).toBe(1)
  })
```

with:

```ts
  it('retires a capability and reports usage', async () => {
    const retired = await retireCapability('reports.monthly')
    expect(retired.capability.status).toBe('RETIRED')
    expect(retired.usage.liveOverrides).toBe(1)
  })
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/api/capabilities.test.ts -t "retires a capability"`
Expected: fails — `retired.capability` is `undefined` against the current flat mock/type.

- [ ] **Step 3: Fix `api/capabilities.ts`**

Replace:

```ts
export function retireCapability(key: string) {
  return apiPost<Capability & { usage: { plans: string[]; liveOverrides: number } }>(`/capabilities/${key}/retire`)
}
```

with:

```ts
export interface CapabilityRetireResult {
  capability: Capability
  usage: { plans: string[]; liveOverrides: number }
}

export function retireCapability(key: string) {
  return apiPost<CapabilityRetireResult>(`/capabilities/${key}/retire`)
}
```

- [ ] **Step 4: Fix the MSW handler**

In `handlers.ts`, replace:

```ts
  http.post('/admin/v1/capabilities/:key/retire', ({ params }) => {
    const cap = db.capabilities.find((c) => c.key === params.key)
    if (!cap) return problem(404, 'entitlement/unknown-capability', `No capability '${params.key}'.`)
    if (cap.status === 'RETIRED') return problem(409, 'entitlement/validation-failed', 'Already retired.')
    cap.status = 'RETIRED'
    return HttpResponse.json({ ...cap, usage: { plans: ['pro'], liveOverrides: 1 } })
  }),
```

with:

```ts
  http.post('/admin/v1/capabilities/:key/retire', ({ params }) => {
    const cap = db.capabilities.find((c) => c.key === params.key)
    if (!cap) return problem(404, 'entitlement/unknown-capability', `No capability '${params.key}'.`)
    if (cap.status === 'RETIRED') return problem(409, 'entitlement/validation-failed', 'Already retired.')
    cap.status = 'RETIRED'
    return HttpResponse.json({ capability: cap, usage: { plans: ['pro'], liveOverrides: 1 } })
  }),
```

- [ ] **Step 5: Run `tsc` and the full test suite**

Run: `npx tsc -b --force` — expected clean (`CapabilityDetailRoute.tsx` doesn't destructure the retire result, so no other call site breaks).
Run: `npm test` — expected all green.

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/api/capabilities.ts \
        management/frontend/management-ui/src/api/capabilities.test.ts \
        management/frontend/management-ui/src/test/mocks/handlers.ts
git commit -m "fix(management-ui): capability retire response is {capability, usage}, not a flat merge"
```

---

### Task 4: Fix the remaining response-shape type lies (`setAccountPlan`, `addOverride`/`removeOverride`, `listPlans`)

Three more declared types are wrong but currently unread beyond the one field each consumer actually uses:

- `setAccountPlan` declares `AccountDetail & {retainedOverrideCount}`; the real `PlanReassignResponseDto` is only `{account, planKey, retainedOverrideCount, snapshotVersion}`.
- `addOverride` declares `override: Override` (a full object); the real `OverrideMutationResponseDto` only has `overrideId: string`. `removeOverride` under-declares the same DTO — it's missing `overrideId` and `changeVisibleEverywhereWithinSeconds`, both of which the real DELETE response actually carries (the same `OverrideMutationResponseDto` backs both create and delete). Widening `removeOverride`'s type lets its liveness banner read the authoritative per-response value instead of falling back to `/admin/v1/meta`, matching how `addOverride`'s already does it.
- `listPlans` declares a `snapshotVersion` the real `GET /admin/v1/plans` never returns.

**Files:**
- Modify: `management/frontend/management-ui/src/api/accounts.ts`
- Modify: `management/frontend/management-ui/src/api/plans.ts`
- Modify: `management/frontend/management-ui/src/api/accounts.test.ts`
- Modify: `management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.tsx`
- Modify: `management/frontend/management-ui/src/test/mocks/handlers.ts`

**Interfaces:**
- Produces: `setAccountPlan` → `{account, planKey, retainedOverrideCount, snapshotVersion}`. `addOverride`/`removeOverride` → `{overrideId: string; decision: Decision; snapshotVersion: number; changeVisibleEverywhereWithinSeconds: number}`. `listPlans` → `{plans: Plan[]}` (no `snapshotVersion`).

- [ ] **Step 1: Fix the failing assumption in `accounts.test.ts` first**

Replace:

```ts
  it('creates and then removes an override', async () => {
    const created = await addOverride('acct_9931', {
      capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 10 }, reason: 'Test grant',
    })
    await expect(removeOverride('acct_9931', created.override.id)).resolves.toMatchObject({ snapshotVersion: 48212 })
  })
```

with:

```ts
  it('creates and then removes an override', async () => {
    const created = await addOverride('acct_9931', {
      capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 10 }, reason: 'Test grant',
    })
    await expect(removeOverride('acct_9931', created.overrideId)).resolves.toMatchObject({ snapshotVersion: 48212 })
  })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run src/api/accounts.test.ts`
Expected: `created.overrideId` is `undefined` against the current mock/type — the "removes an override" assertion fails (it calls `removeOverride` with `undefined` as the id).

- [ ] **Step 3: Fix `api/accounts.ts`**

Replace the whole file's `setAccountPlan`, `AddOverrideInput`/`addOverride`, and `removeOverride` section:

```ts
export function setAccountPlan(external: string, input: { planKey: string; source: AssignmentSource; actor: string; reason?: string }) {
  return apiPut<AccountDetail & { retainedOverrideCount: number }>(`/accounts/${external}/plan`, input)
}

export interface AddOverrideInput {
  capability: string
  kind: OverrideKind
  value: EntitlementValue
  reason: string
}

export function addOverride(external: string, input: AddOverrideInput) {
  return apiPost<{ override: Override; decision: Decision; snapshotVersion: number; changeVisibleEverywhereWithinSeconds: number }>(
    `/accounts/${external}/overrides`,
    input,
  )
}

export function removeOverride(external: string, id: string, reason?: string) {
  return apiDelete<{ decision: Decision; snapshotVersion: number }>(`/accounts/${external}/overrides/${id}`, reason ? { reason } : undefined)
}
```

with:

```ts
export interface PlanReassignResult {
  account: string
  planKey: string
  retainedOverrideCount: number
  snapshotVersion: number
}

export function setAccountPlan(external: string, input: { planKey: string; source: AssignmentSource; actor: string; reason?: string }) {
  return apiPut<PlanReassignResult>(`/accounts/${external}/plan`, input)
}

export interface AddOverrideInput {
  capability: string
  kind: OverrideKind
  value: EntitlementValue
  reason: string
}

export interface OverrideMutationResult {
  overrideId: string
  decision: Decision
  snapshotVersion: number
  changeVisibleEverywhereWithinSeconds: number
}

export function addOverride(external: string, input: AddOverrideInput) {
  return apiPost<OverrideMutationResult>(`/accounts/${external}/overrides`, input)
}

export function removeOverride(external: string, id: string, reason?: string) {
  return apiDelete<OverrideMutationResult>(`/accounts/${external}/overrides/${id}`, reason ? { reason } : undefined)
}
```

Also drop `Override` from this file's top import (it's no longer referenced): change

```ts
import type { AccountDetail, AccountSummary, AssignmentSource, Decision, Override, OverrideKind } from '../types/domain'
```

to

```ts
import type { AccountDetail, AccountSummary, AssignmentSource, Decision, OverrideKind } from '../types/domain'
```

(`AccountDetail` stays — `getAccount` still returns it.)

- [ ] **Step 4: Fix `api/plans.ts`**

Replace:

```ts
export function listPlans() {
  return apiGet<{ plans: Plan[]; snapshotVersion: number }>('/plans')
}
```

with:

```ts
export function listPlans() {
  return apiGet<{ plans: Plan[] }>('/plans')
}
```

- [ ] **Step 5: Fix the MSW handlers**

In `handlers.ts`, replace the plan-reassignment handler:

```ts
  http.put('/admin/v1/accounts/:external/plan', async ({ params, request }) => {
    if (params.external !== db.account.account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    const body = (await request.json()) as { planKey: string; source: 'PERSON' | 'SYSTEM'; actor: string }
    db.account.plan = { key: body.planKey, name: body.planKey, assignedAt: new Date(0).toISOString(), assignedBy: body.actor, source: body.source }
    return HttpResponse.json({ ...db.account, retainedOverrideCount: db.account.overrides.length })
  }),
```

with:

```ts
  http.put('/admin/v1/accounts/:external/plan', async ({ params, request }) => {
    if (params.external !== db.account.account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    const body = (await request.json()) as { planKey: string; source: 'PERSON' | 'SYSTEM'; actor: string }
    db.account.plan = { key: body.planKey, name: body.planKey, assignedAt: new Date(0).toISOString(), assignedBy: body.actor, source: body.source }
    return HttpResponse.json({ account: db.account.account, planKey: body.planKey, retainedOverrideCount: db.account.overrides.length, snapshotVersion: db.account.snapshotVersion })
  }),
```

Replace the add-override handler's response line:

```ts
    return HttpResponse.json({ override: created, decision: { allowed: true, value: body.value, trace: RESULT_TRACE }, snapshotVersion: 48212, changeVisibleEverywhereWithinSeconds: 60 }, { status: 201 })
```

with:

```ts
    return HttpResponse.json({ overrideId: created.id, decision: { allowed: true, value: body.value, trace: RESULT_TRACE }, snapshotVersion: 48212, changeVisibleEverywhereWithinSeconds: 60 }, { status: 201 })
```

Replace the remove-override handler:

```ts
  http.delete('/admin/v1/accounts/:external/overrides/:id', ({ params }) => {
    if (params.external !== db.account.account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    db.account.overrides = db.account.overrides.filter((o) => o.id !== params.id)
    return HttpResponse.json({ decision: { allowed: true, value: { type: 'QUANTITY', amount: 50 }, trace: RESULT_TRACE }, snapshotVersion: 48212 })
  }),
```

with:

```ts
  http.delete('/admin/v1/accounts/:external/overrides/:id', ({ params }) => {
    if (params.external !== db.account.account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    db.account.overrides = db.account.overrides.filter((o) => o.id !== params.id)
    return HttpResponse.json({
      overrideId: String(params.id),
      decision: { allowed: true, value: { type: 'QUANTITY', amount: 50 }, trace: RESULT_TRACE },
      snapshotVersion: 48212,
      changeVisibleEverywhereWithinSeconds: 60,
    })
  }),
```

And the plans-list handler:

```ts
  http.get('/admin/v1/plans', () => HttpResponse.json({ plans: db.plans, snapshotVersion: 48211 })),
```

to:

```ts
  http.get('/admin/v1/plans', () => HttpResponse.json({ plans: db.plans })),
```

- [ ] **Step 6: Use the now-authoritative liveness number in `AccountDetailRoute.tsx`'s remove-override result**

`removeOverride`'s response now genuinely carries `changeVisibleEverywhereWithinSeconds`, the same way `addOverride`'s already did — read it the same way instead of falling back to the separate `/meta` query. Replace:

```tsx
      {removedDecision && (
        <div className="app-panel">
          <h3>Restored value</h3>
          <TraceView trace={removedDecision.trace} />
          {meta.data && <SaveConfirmation seconds={meta.data.changeVisibleEverywhereWithinSeconds} />}
        </div>
      )}
```

with:

```tsx
      {removedDecision && removeMutation.data && (
        <div className="app-panel">
          <h3>Restored value</h3>
          <TraceView trace={removedDecision.trace} />
          <SaveConfirmation seconds={removeMutation.data.changeVisibleEverywhereWithinSeconds} />
        </div>
      )}
```

`meta` stays imported/queried in this file — the change-plan confirmation (a few lines above) still falls back to it, since `PlanReassignResult` carries no liveness-seconds field of its own.

- [ ] **Step 7: Run `tsc` and the full test suite**

Run: `npx tsc -b --force` — expected clean.
Run: `npm test` — expected all green, including `AccountDetailRoute.test.tsx`'s remove-override liveness assertion (the rendered text is unchanged; only its data source moved from `meta.data` to `removeMutation.data`, and both mocks return `60`).

- [ ] **Step 8: Commit**

```bash
git add management/frontend/management-ui/src/api/accounts.ts \
        management/frontend/management-ui/src/api/plans.ts \
        management/frontend/management-ui/src/api/accounts.test.ts \
        management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.tsx \
        management/frontend/management-ui/src/test/mocks/handlers.ts
git commit -m "fix(management-ui): narrow setAccountPlan/addOverride/removeOverride/listPlans to their real response shapes"
```

---

### Task 5: Add the `requiredRole` route metadata (ui-screens.md's cross-cutting section)

`ui-screens.md` states: "Route definitions carry a `requiredRole` field that is currently unread, so enabling enforcement later is a guard, not a rewrite" (c37). No route in `router.tsx` carries any such field today. This task adds it as TanStack Router `staticData`, genuinely unread by any logic — purely structural, matching the contract.

**Files:**
- Modify: `management/frontend/management-ui/src/router.tsx`
- Create: `management/frontend/management-ui/src/router.test.tsx`

**Interfaces:**
- Produces: every screen route's `.options.staticData.requiredRole` is `{ read: Role }` or `{ read: Role; write: Role }`, where `Role = 'VIEWER' | 'ADMINISTRATOR' | 'EXCEPTION_MANAGER'` (the three role names from ui-screens.md's "Roles" section, §9).

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it } from 'vitest'
import {
  capabilitiesListRoute, capabilityDetailRoute, plansListRoute, planEditorRoute,
  accountsListRoute, accountDetailRoute, checkerRoute, historyRoute,
} from './router'

describe('router requiredRole metadata', () => {
  it('marks every write-capable screen as requiring its stated role', () => {
    expect(capabilitiesListRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(capabilityDetailRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(plansListRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(planEditorRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(accountsListRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'EXCEPTION_MANAGER' })
    expect(accountDetailRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'EXCEPTION_MANAGER' })
  })

  it('marks the read-only screens with no write role', () => {
    expect(checkerRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER' })
    expect(historyRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER' })
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/router.test.tsx`
Expected: fails — none of the routes are exported by name yet (only `router` is exported today), and none carry `staticData`.

- [ ] **Step 3: Rewrite `router.tsx`**

Replace the whole file with:

```tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'
import { PlansListRoute } from './routes/plans/PlansListRoute'
import { PlanEditorRoute } from './routes/plans/PlanEditorRoute'
import { AccountsListRoute } from './routes/accounts/AccountsListRoute'
import { AccountDetailRoute } from './routes/accounts/AccountDetailRoute'
import { CheckerRoute } from './routes/checker/CheckerRoute'
import { HistoryRoute } from './routes/history/HistoryRoute'

// The three roles of ui-screens.md §9 ("Roles (c37 — not implemented in v1)"). Nothing reads
// `requiredRole` today — every screen is fully usable by anyone who can reach the app — but the
// field is present on every route so enabling enforcement later is a guard, not a rewrite.
export type Role = 'VIEWER' | 'ADMINISTRATOR' | 'EXCEPTION_MANAGER'

declare module '@tanstack/react-router' {
  interface StaticDataRouteOption {
    requiredRole?: { read: Role; write?: Role }
  }
}

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })

export const capabilitiesListRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const capabilityDetailRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute />,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const plansListRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const planEditorRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute />,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const accountsListRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute,
  staticData: { requiredRole: { read: 'VIEWER', write: 'EXCEPTION_MANAGER' } },
})
export const accountDetailRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/accounts/$external', component: () => <AccountDetailRoute />,
  staticData: { requiredRole: { read: 'VIEWER', write: 'EXCEPTION_MANAGER' } },
})
export const checkerRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/checker', component: CheckerRoute,
  staticData: { requiredRole: { read: 'VIEWER' } },
})
export const historyRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/history', component: HistoryRoute,
  staticData: { requiredRole: { read: 'VIEWER' } },
})

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesListRoute, capabilityDetailRoute, plansListRoute, planEditorRoute,
  accountsListRoute, accountDetailRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

(The route objects were previously unexported locals; this task exports them by name so the new test — and any future authorization guard — can reference them directly. `indexRoute`, the home/landing page, is not one of ui-screens.md's five screens and is left without `staticData`, consistent with `requiredRole` being declared optional.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/router.test.tsx`
Expected: both assertions pass.

- [ ] **Step 5: Run `tsc` and the full test suite**

Run: `npx tsc -b --force` — expected clean.
Run: `npm test` — expected all green; exporting the previously-local route consts changes no runtime behavior other routes/tests depend on.

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/router.tsx management/frontend/management-ui/src/router.test.tsx
git commit -m "feat(management-ui): route definitions carry requiredRole per ui-screens.md §9 (c37)"
```

---

## Final Verification

- [ ] `npx tsc -b --force` — clean.
- [ ] `npm test` — full green run (expect 107 + the new tests from Tasks 1–5 above).
- [ ] `npm run build` — succeeds.
- [ ] `npm run lint` — clean.
- [ ] Manual smoke test against the real backend (the matching admin-API fixes landed as the post-merge addendum inside `2026-08-09-entitlement-service-api-layer.md`, Tasks 11–12; there is no separate `entitlement-admin-api-contract-fixes` plan and there never was): `spring-boot:run` the service, `npm run dev -- --host 0.0.0.0` the SPA, and walk through: search accounts → open one → the link now resolves; create an account → it appears in the list; retire a capability → the confirmation shows real usage counts instead of crashing.
