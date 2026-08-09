# Entitlement Service — Operator UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the React operator SPA for the entitlement service's five §9 screens (Capabilities, Plans, Accounts, Checker, History) against the documented `/admin/v1` contract, styled from the canonical Solovis design tokens.

**Architecture:** TanStack Router (code-based route tree) + TanStack Query for server state, a thin typed `fetch` wrapper per admin-api.md (no codegen — no OpenAPI document exists yet since the backend is still scaffolding), and three shared components (`CapabilityTree`, `TraceView`, `ValueEditor`) that every screen composes. MSW mocks the admin API in tests so the frontend is fully testable ahead of the backend.

**Tech Stack:** React 19, TypeScript 6, Vite 8, `@tanstack/react-router` 1.170.x, `@tanstack/react-query` 5.101.x, Vitest 4 + `@testing-library/react` 16 + `jsdom`, MSW 2.15 for API mocking, `oxlint` (already configured).

## Global Constraints

- Base project directory for every path below: `management/frontend/management-ui/` (the existing Vite scaffold — do not create a new `entitlement-ui/` directory; the repo already committed this name in "management ui scaffolding").
- Styling comes only from `.claude/design/solovis/tokens.css`, copied into `src/styles/tokens.css` (Task 1). No colour, radius, spacing or type value may be introduced that isn't one of its custom properties or `.sv-*` classes.
- Every mutation response's "when live" message is **"Saved. Active everywhere within {N} seconds."** where `{N}` comes from `GET /admin/v1/meta` → `changeVisibleEverywhereWithinSeconds` — never a hard-coded `60` (c41).
- Auth is not implemented (v1 decision). Every screen is reachable by anyone; `AppLayout` shows the persistent banner **"Unauthenticated instance — all actions are open and audited as `dev-operator`."** verbatim.
- Value encoding is exactly the three-variant shape in `contracts/README.md`: `{type:'SWITCH',enabled}`, `{type:'QUANTITY',amount}` or `{type:'QUANTITY',unlimited:true}`, `{type:'TIER',tier,ordinal?}`. `unlimited` is never a large number.
- Accounts are addressed by external id, capabilities by dotted key, in every route and every test fixture.
- TypeScript config has `verbatimModuleSyntax: true` — use `import type { X }` for type-only imports, never a bare `import { X }` for a type.
- Dev server must bind `0.0.0.0` (this machine is headless, accessed over SSH) — set in `vite.config.ts` (Task 1).
- **Commit discipline:** each task's commit stages only the files that task lists under "Files" — never `git add -A` or `git add .`. The working tree currently has an unrelated pending deletion (`homepage.html`) and an untracked `refs/` directory from before this plan; leave both alone unless a task explicitly names them (none does).
- No task installs or wires an OpenAPI-generated client — the backend has no OpenAPI document yet (only `package-info.java` stubs exist). Hand-written types in `src/types/` are the source of truth until one exists.

---

## Phase 0 — Foundation

### Task 1: Toolchain, dependencies, design tokens, scaffold cleanup

**Files:**
- Modify: `management/frontend/management-ui/package.json`
- Modify: `management/frontend/management-ui/vite.config.ts`
- Create: `management/frontend/management-ui/src/test/setup.ts`
- Create: `management/frontend/management-ui/src/styles/tokens.css` (copied from `.claude/design/solovis/tokens.css`)
- Create: `management/frontend/management-ui/src/styles/app.css`
- Modify: `management/frontend/management-ui/src/index.css`
- Modify: `management/frontend/management-ui/src/main.tsx`
- Delete: `management/frontend/management-ui/src/App.tsx`, `src/App.css`, `src/assets/react.svg`, `src/assets/vite.svg`, `src/assets/hero.png`

**Interfaces:**
- Produces: `src/styles/tokens.css` (the `--sv-*` custom properties and `.sv-*` classes every later component uses), `src/styles/app.css` (app-shell chrome classes: `.app-topbar`, `.app-navbar`, `.app-canvas`, `.app-panel`), a working `npm test` (Vitest) and `npm run dev` (Vite, bound to `0.0.0.0`).

- [ ] **Step 1: Install runtime and dev dependencies**

```bash
cd management/frontend/management-ui
npm install @tanstack/react-router@^1.170.23 @tanstack/react-query@^5.101.4
npm install -D vitest@^4.1.10 @vitest/coverage-v8@^4.1.10 jsdom@^30.0.1 \
  @testing-library/react@^16.3.2 @testing-library/jest-dom@^7.0.0 \
  @testing-library/user-event@^14.6.3 msw@^2.15.0
```

- [ ] **Step 2: Copy the canonical design tokens into the project**

```bash
mkdir -p management/frontend/management-ui/src/styles
cp .claude/design/solovis/tokens.css management/frontend/management-ui/src/styles/tokens.css
```

Add a one-line header comment noting the source so a future re-sync is obvious:

```css
/* Copied from .claude/design/solovis/tokens.css (canonical source — do not hand-edit values here). */
```

(prepend this line above the existing file header, don't otherwise touch the file's contents).

- [ ] **Step 3: Write the app-shell stylesheet**

```css
/* src/styles/app.css — application chrome, not marketing chrome. Uses --sv-app-* tokens. */
@import './tokens.css';

* { box-sizing: border-box; }

body {
  margin: 0;
  font-family: var(--sv-font);
  font-size: var(--sv-type-14);
  color: var(--sv-text);
  background: var(--sv-app-canvas);
}

.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.app-topbar {
  background: var(--sv-app-topbar);
  color: var(--sv-surface);
  padding: var(--sv-space-12) var(--sv-space-24);
  font-weight: var(--sv-weight-heading);
  font-size: var(--sv-type-16);
}

.app-banner {
  background: var(--sv-slate-800);
  color: var(--sv-surface-alt);
  padding: var(--sv-space-8) var(--sv-space-24);
  font-size: var(--sv-type-12);
}

.app-navbar {
  background: var(--sv-app-navbar);
  display: flex;
  gap: var(--sv-space-24);
  padding: 0 var(--sv-space-24);
}

.app-navbar a {
  display: inline-block;
  padding: var(--sv-space-12) 0;
  color: var(--sv-accent-pale);
  text-decoration: none;
  font-weight: var(--sv-weight-ui);
  font-size: var(--sv-type-14);
  border-bottom: 2px solid transparent;
}

.app-navbar a:hover,
.app-navbar a.active {
  color: var(--sv-surface);
  border-bottom-color: var(--sv-accent);
}

.app-canvas {
  flex: 1;
  padding: var(--sv-space-32);
  max-width: var(--sv-content-max);
  width: 100%;
  margin: 0 auto;
}

.app-panel {
  background: var(--sv-app-surface);
  border: 1px solid var(--sv-app-border);
  border-radius: var(--sv-radius-card);
  padding: var(--sv-space-24);
}

.app-panel + .app-panel {
  margin-top: var(--sv-space-24);
}

.app-page-title {
  font-size: var(--sv-type-28);
  font-weight: var(--sv-weight-heading);
  line-height: var(--sv-lh-28);
  margin: 0 0 var(--sv-space-24);
}
```

- [ ] **Step 4: Replace `src/index.css`**

```css
@import './styles/app.css';
```

- [ ] **Step 5: Remove the Vite/React scaffold template**

```bash
cd management/frontend/management-ui
rm -f src/App.tsx src/App.css src/assets/react.svg src/assets/vite.svg src/assets/hero.png
```

`src/main.tsx` is rewritten in Task 6 (it currently imports the deleted `App.tsx`, so the app will not build between this step and Task 6 — that is expected and corrected within this same task, Step 6 below, with a placeholder-free interim `main.tsx`).

- [ ] **Step 6: Write an interim `src/main.tsx`** (Task 6 replaces this with the router-wired version; this version is a complete, working entry point on its own — not a stub)

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'

function App() {
  return <div className="app-canvas app-panel">Entitlement Service — operator UI is being assembled.</div>
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

- [ ] **Step 7: Configure Vite for the headless dev host and Vitest**

```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    proxy: {
      '/admin': 'http://localhost:8081',
      '/v1': 'http://localhost:8081',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
  },
})
```

- [ ] **Step 8: Write the Vitest setup file**

```ts
// src/test/setup.ts
import '@testing-library/jest-dom/vitest'
```

(MSW server lifecycle is added to this same file in Task 5, once handlers exist — not here, since no handlers exist yet and adding an empty `setupServer()` would be dead code.)

- [ ] **Step 9: Add `test` script and confirm the toolchain boots**

Edit `package.json` scripts to add:

```json
"test": "vitest run",
"test:watch": "vitest"
```

Run:

```bash
npm run build
npm test
```

Expected: `tsc -b && vite build` succeeds (no leftover references to the deleted `App.tsx`/assets); `vitest run` reports "No test files found" (exit code 0 is fine — Vitest 4 exits 0 on an empty suite by default; if it exits non-zero, add `--passWithNoTests` to the `test` script).

- [ ] **Step 10: Commit**

```bash
git add management/frontend/management-ui/package.json \
        management/frontend/management-ui/package-lock.json \
        management/frontend/management-ui/vite.config.ts \
        management/frontend/management-ui/src/test/setup.ts \
        management/frontend/management-ui/src/styles/tokens.css \
        management/frontend/management-ui/src/styles/app.css \
        management/frontend/management-ui/src/index.css \
        management/frontend/management-ui/src/main.tsx
git rm management/frontend/management-ui/src/App.tsx management/frontend/management-ui/src/App.css \
       management/frontend/management-ui/src/assets/react.svg management/frontend/management-ui/src/assets/vite.svg \
       management/frontend/management-ui/src/assets/hero.png
git commit -m "frontend: toolchain, design tokens, and app-shell stylesheet"
```

---

### Task 2: Value encoding and domain types

**Files:**
- Create: `management/frontend/management-ui/src/types/value.ts`
- Create: `management/frontend/management-ui/src/types/value.test.ts`
- Create: `management/frontend/management-ui/src/types/domain.ts`

**Interfaces:**
- Produces: `EntitlementValue`, `ValueType`, `formatValue(value, tiers?) => string`, `valuesEqual(a, b) => boolean`, `zeroValueFor(valueType) => EntitlementValue` (used by forms to seed a default input) from `value.ts`; `Capability`, `CapabilityTier`, `Plan`, `PlanEntitlementDiffEntry`, `Override`, `OverrideEffect`, `EntitlementRow`, `AccountDetail`, `AccountSummary`, `Trace`, `AuditEvent` from `domain.ts`. Every later task imports value/domain types from these two files — no task redefines them.

- [ ] **Step 1: Write the failing tests for value formatting**

```ts
// src/types/value.test.ts
import { describe, expect, it } from 'vitest'
import { formatValue, valuesEqual, zeroValueFor } from './value'
import type { CapabilityTier } from './domain'

const tiers: CapabilityTier[] = [
  { tier: 'community', ordinal: 0, displayName: 'Community' },
  { tier: 'gold', ordinal: 2, displayName: 'Gold' },
]

describe('formatValue', () => {
  it('renders a switch', () => {
    expect(formatValue({ type: 'SWITCH', enabled: true })).toBe('On')
    expect(formatValue({ type: 'SWITCH', enabled: false })).toBe('Off')
  })

  it('renders a bounded quantity', () => {
    expect(formatValue({ type: 'QUANTITY', amount: 50 })).toBe('50')
  })

  it('renders unlimited as a distinct word, never a number', () => {
    expect(formatValue({ type: 'QUANTITY', unlimited: true })).toBe('Unlimited')
  })

  it('renders a tier by its declared display name when tiers are supplied', () => {
    expect(formatValue({ type: 'TIER', tier: 'gold', ordinal: 2 }, tiers)).toBe('Gold')
  })

  it('falls back to the raw tier key when no tier list is supplied', () => {
    expect(formatValue({ type: 'TIER', tier: 'gold', ordinal: 2 })).toBe('gold')
  })
})

describe('valuesEqual', () => {
  it('treats two unlimited quantities as equal regardless of stray fields', () => {
    expect(valuesEqual({ type: 'QUANTITY', unlimited: true }, { type: 'QUANTITY', unlimited: true })).toBe(true)
  })

  it('treats an unlimited quantity and a large amount as different', () => {
    expect(valuesEqual({ type: 'QUANTITY', unlimited: true }, { type: 'QUANTITY', amount: 999999 })).toBe(false)
  })

  it('treats equal switches as equal and different switches as different', () => {
    expect(valuesEqual({ type: 'SWITCH', enabled: true }, { type: 'SWITCH', enabled: true })).toBe(true)
    expect(valuesEqual({ type: 'SWITCH', enabled: true }, { type: 'SWITCH', enabled: false })).toBe(false)
  })

  it('treats equal tiers as equal by tier key alone', () => {
    expect(valuesEqual({ type: 'TIER', tier: 'gold', ordinal: 2 }, { type: 'TIER', tier: 'gold' })).toBe(true)
  })
})

describe('zeroValueFor', () => {
  it('seeds a SWITCH as off', () => {
    expect(zeroValueFor('SWITCH')).toEqual({ type: 'SWITCH', enabled: false })
  })
  it('seeds a QUANTITY as amount 0, never unlimited', () => {
    expect(zeroValueFor('QUANTITY')).toEqual({ type: 'QUANTITY', amount: 0 })
  })
})
```

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
cd management/frontend/management-ui && npx vitest run src/types/value.test.ts
```

Expected: FAIL — `./value` has no exported members (the module doesn't exist yet).

- [ ] **Step 3: Write `src/types/domain.ts`** (needed first since `value.ts`'s `formatValue` takes `CapabilityTier[]`)

```ts
// src/types/domain.ts
import type { EntitlementValue, ValueType } from './value'

export interface CapabilityTier {
  tier: string
  ordinal: number
  displayName: string
}

export type CapabilityStatus = 'ACTIVE' | 'RETIRED'

export interface Capability {
  key: string
  area: string
  displayName: string
  description: string | null
  valueType: ValueType
  default: EntitlementValue
  offValue: EntitlementValue | null
  tiers: CapabilityTier[]
  status: CapabilityStatus
}

export type PlanStatus = 'ACTIVE' | 'ARCHIVED'

export interface Plan {
  key: string
  name: string
  description?: string | null
  status: PlanStatus
  isDefaultForNewAccounts: boolean
  accountCount: number
  entitlementCount: number
}

export interface PlanEntitlement {
  capability: string
  value: EntitlementValue
}

export interface PlanEntitlementDiffEntry {
  capability: string
  before: EntitlementValue | null
  after: EntitlementValue | null
  note?: string
}

export type AssignmentSource = 'PERSON' | 'SYSTEM'

export type OverrideKind = 'GRANT' | 'HOLD'

export type OverrideEffect =
  | 'WINNING'
  | 'OVERRIDDEN_BY_HOLD'
  | 'SUPERSEDED_BY_GRANT'
  | 'SUPERSEDED_BY_STRICTER_HOLD'
  | 'NO_EFFECT_PLAN_MORE_GENEROUS'
  | 'NO_EFFECT_NOT_MORE_RESTRICTIVE'

export interface Override {
  id: string
  capability: string
  kind: OverrideKind
  value: EntitlementValue
  reason: string
  createdBy: string
  createdAt: string
  effectNow: OverrideEffect
}

export type EntitlementSource = 'CAPABILITY_DEFAULT' | 'PLAN' | 'GRANT' | 'HOLD'

export interface EntitlementRow {
  capability: string
  area: string
  allowed: boolean
  value: EntitlementValue
  source: EntitlementSource
  sourceDetail: { overrideId?: string; reason?: string; planKey?: string } | null
}

export interface AccountDetail {
  account: string
  name: string | null
  status: 'ACTIVE' | 'CLOSED'
  plan: {
    key: string
    name: string
    assignedAt: string
    assignedBy: string
    source: AssignmentSource
  }
  snapshotVersion: number
  entitlements: EntitlementRow[]
  overrides: Override[]
}

export interface AccountSummary {
  external: string
  name: string | null
  planKey: string
}

export type TraceBaselineSource = 'PLAN' | 'CAPABILITY_DEFAULT'

export interface TraceCandidate {
  overrideId: string
  value: EntitlementValue
  reason: string
  createdBy: string
  createdAt: string
  outcome: string
}

export interface Trace {
  baseline: {
    source: TraceBaselineSource
    planKey?: string
    value: EntitlementValue
    note: string
  }
  grants: TraceCandidate[]
  grantStep: { applied: boolean; winner?: string; value?: EntitlementValue; why?: string; note?: string }
  holds: TraceCandidate[]
  holdStep: { applied: boolean; winner?: string; value?: EntitlementValue; why?: string; note?: string }
  result: {
    value: EntitlementValue
    allowed: boolean
    allowedReason: 'NO_OFF_VALUE_DECLARED' | 'DIFFERS_FROM_OFF_VALUE' | 'EQUALS_OFF_VALUE'
  }
}

export interface Decision {
  account: string
  capability: string
  allowed: boolean
  value: EntitlementValue
  snapshotVersion: number
  evaluatedAt: string
  trace: Trace
}

export type AuditActorKind = 'PERSON' | 'SYSTEM'
export type AuditSource = 'UI' | 'BILLING' | 'API' | 'SEED'
export type AuditEntityType =
  | 'CAPABILITY' | 'CAPABILITY_TIER' | 'PLAN' | 'PLAN_ENTITLEMENT'
  | 'ACCOUNT' | 'ACCOUNT_PLAN' | 'DEFAULT_PLAN' | 'OVERRIDE'
export type AuditAction = 'CREATE' | 'UPDATE' | 'RETIRE' | 'ARCHIVE' | 'REMOVE' | 'ASSIGN' | 'DESIGNATE'

export interface AuditEvent {
  seq: number
  occurredAt: string
  actor: { id: string; kind: AuditActorKind }
  source: AuditSource
  entityType: AuditEntityType
  entityId: string
  action: AuditAction
  planKey: string | null
  account: string | null
  capability: string | null
  before: EntitlementValue | null
  after: EntitlementValue | null
  reason: string | null
  affectedAccountCount: number | null
}
```

- [ ] **Step 4: Write `src/types/value.ts`**

```ts
// src/types/value.ts
import type { CapabilityTier } from './domain'

export type EntitlementValue =
  | { type: 'SWITCH'; enabled: boolean }
  | { type: 'QUANTITY'; amount: number }
  | { type: 'QUANTITY'; unlimited: true }
  | { type: 'TIER'; tier: string; ordinal?: number }

export type ValueType = EntitlementValue['type']

export function formatValue(value: EntitlementValue, tiers?: CapabilityTier[]): string {
  switch (value.type) {
    case 'SWITCH':
      return value.enabled ? 'On' : 'Off'
    case 'QUANTITY':
      return 'amount' in value ? String(value.amount) : 'Unlimited'
    case 'TIER': {
      const declared = tiers?.find((t) => t.tier === value.tier)
      return declared?.displayName ?? value.tier
    }
  }
}

export function valuesEqual(a: EntitlementValue, b: EntitlementValue): boolean {
  if (a.type !== b.type) return false
  if (a.type === 'SWITCH' && b.type === 'SWITCH') return a.enabled === b.enabled
  if (a.type === 'QUANTITY' && b.type === 'QUANTITY') {
    const aAmount = 'amount' in a ? a.amount : null
    const bAmount = 'amount' in b ? b.amount : null
    return aAmount === bAmount
  }
  if (a.type === 'TIER' && b.type === 'TIER') return a.tier === b.tier
  return false
}

export function zeroValueFor(valueType: ValueType): EntitlementValue {
  switch (valueType) {
    case 'SWITCH':
      return { type: 'SWITCH', enabled: false }
    case 'QUANTITY':
      return { type: 'QUANTITY', amount: 0 }
    case 'TIER':
      return { type: 'TIER', tier: '' }
  }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

```bash
cd management/frontend/management-ui && npx vitest run src/types/value.test.ts
```

Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/types/value.ts \
        management/frontend/management-ui/src/types/value.test.ts \
        management/frontend/management-ui/src/types/domain.ts
git commit -m "frontend: value encoding and domain types"
```

---

### Task 3: Test infrastructure — MSW fixtures, mock server, render helper, query keys

**Files:**
- Create: `management/frontend/management-ui/src/test/mocks/fixtures.ts`
- Create: `management/frontend/management-ui/src/test/mocks/handlers.ts`
- Create: `management/frontend/management-ui/src/test/mocks/server.ts`
- Create: `management/frontend/management-ui/src/test/testUtils.tsx`
- Modify: `management/frontend/management-ui/src/test/setup.ts`
- Create: `management/frontend/management-ui/src/queries/keys.ts`

**Interfaces:**
- Produces: `server` (MSW `SetupServerApi`) and `resetDb()` from `mocks/server.ts`; `renderWithProviders(ui, options?: {initialPath?}) => RenderResult & {router}` from `testUtils.tsx` — mounts both `QueryClientProvider` and a minimal single-route `RouterProvider`, since every screen from Task 10 onward renders `<Link>`/calls `useParams` and needs router context or it crashes; `queryKeys` object from `queries/keys.ts`. Every later API and component test imports from these three files instead of building its own fixtures, so every screen's tests exercise the same account/plan/capability data the contracts document.

- [ ] **Step 1: Write the fixture data, matching the contract examples exactly**

```ts
// src/test/mocks/fixtures.ts
import type { Capability, Plan, AccountDetail, AuditEvent } from '../../types/domain'

export function makeCapabilities(): Capability[] {
  return [
    {
      key: 'reports.monthly', area: 'reports', displayName: 'Monthly reports', description: 'Reports an account may generate per month.',
      valueType: 'QUANTITY', default: { type: 'QUANTITY', amount: 0 }, offValue: null, tiers: [], status: 'ACTIVE',
    },
    {
      key: 'api.access', area: 'api', displayName: 'API access', description: null,
      valueType: 'SWITCH', default: { type: 'SWITCH', enabled: false }, offValue: null, tiers: [], status: 'ACTIVE',
    },
    {
      key: 'support', area: 'support', displayName: 'Support level', description: null,
      valueType: 'TIER',
      default: { type: 'TIER', tier: 'community', ordinal: 0 },
      offValue: null,
      tiers: [
        { tier: 'community', ordinal: 0, displayName: 'Community' },
        { tier: 'standard', ordinal: 1, displayName: 'Standard' },
        { tier: 'gold', ordinal: 2, displayName: 'Gold' },
      ],
      status: 'ACTIVE',
    },
    {
      key: 'seats', area: 'seats', displayName: 'Seats', description: null,
      valueType: 'QUANTITY', default: { type: 'QUANTITY', unlimited: true }, offValue: null, tiers: [], status: 'ACTIVE',
    },
    {
      key: 'export.parquet', area: 'export', displayName: 'Parquet export', description: null,
      valueType: 'SWITCH', default: { type: 'SWITCH', enabled: false }, offValue: null, tiers: [], status: 'ACTIVE',
    },
  ]
}

export function makePlans(): Plan[] {
  return [
    { key: 'free', name: 'Free', status: 'ACTIVE', isDefaultForNewAccounts: true, accountCount: 71204, entitlementCount: 12 },
    { key: 'pro', name: 'Pro', status: 'ACTIVE', isDefaultForNewAccounts: false, accountCount: 26890, entitlementCount: 41 },
  ]
}

export function makeAccount(): AccountDetail {
  return {
    account: 'acct_9931',
    name: 'Northwind Capital',
    status: 'ACTIVE',
    plan: { key: 'pro', name: 'Pro', assignedAt: '2026-05-04T10:00:00.000Z', assignedBy: 'billing-sync', source: 'SYSTEM' },
    snapshotVersion: 48211,
    entitlements: [
      {
        capability: 'reports.monthly', area: 'reports', allowed: true, value: { type: 'QUANTITY', amount: 0 },
        source: 'HOLD', sourceDetail: { overrideId: 'ovr_7788', reason: 'Suspended pending billing investigation' },
      },
      {
        capability: 'seats', area: 'seats', allowed: true, value: { type: 'QUANTITY', unlimited: true },
        source: 'PLAN', sourceDetail: { planKey: 'pro' },
      },
      {
        capability: 'export.parquet', area: 'export', allowed: false, value: { type: 'SWITCH', enabled: false },
        source: 'CAPABILITY_DEFAULT', sourceDetail: null,
      },
    ],
    overrides: [
      {
        id: 'ovr_4471', capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 200 },
        reason: 'Renewal concession — Q3 pilot', createdBy: 'j.okafor', createdAt: '2026-06-02T09:12:44.000Z',
        effectNow: 'OVERRIDDEN_BY_HOLD',
      },
      {
        id: 'ovr_7788', capability: 'reports.monthly', kind: 'HOLD', value: { type: 'QUANTITY', amount: 0 },
        reason: 'Suspended pending billing investigation', createdBy: 'billing-bot', createdAt: '2026-08-01T02:00:00.000Z',
        effectNow: 'WINNING',
      },
    ],
  }
}

export function makeAuditEvents(): AuditEvent[] {
  return [
    {
      seq: 90114, occurredAt: '2026-08-09T15:10:00.000Z', actor: { id: 'a.reyes', kind: 'PERSON' }, source: 'UI',
      entityType: 'OVERRIDE', entityId: 'ovr_9002', action: 'REMOVE', planKey: null, account: 'acct_9931',
      capability: 'reports.monthly', before: { type: 'QUANTITY', amount: 100 }, after: null,
      reason: 'Investigation closed', affectedAccountCount: null,
    },
    {
      seq: 90113, occurredAt: '2026-08-09T14:30:00.000Z', actor: { id: 'billing-bot', kind: 'SYSTEM' }, source: 'BILLING',
      entityType: 'OVERRIDE', entityId: 'ovr_7788', action: 'CREATE', planKey: null, account: 'acct_9931',
      capability: 'reports.monthly', before: null, after: { type: 'QUANTITY', amount: 0 },
      reason: 'Suspended pending billing investigation', affectedAccountCount: null,
    },
    {
      seq: 90112, occurredAt: '2026-08-09T14:03:10.880Z', actor: { id: 'a.reyes', kind: 'PERSON' }, source: 'UI',
      entityType: 'PLAN_ENTITLEMENT', entityId: 'pro', action: 'UPDATE', planKey: 'pro', account: null,
      capability: 'reports.monthly', before: { type: 'QUANTITY', amount: 50 }, after: { type: 'QUANTITY', amount: 75 },
      reason: null, affectedAccountCount: 26890,
    },
  ]
}

export const RESULT_TRACE = {
  baseline: { source: 'PLAN' as const, planKey: 'pro', value: { type: 'QUANTITY' as const, amount: 50 }, note: "Plan 'pro' sets this capability." },
  grants: [
    { overrideId: 'ovr_4471', value: { type: 'QUANTITY' as const, amount: 200 }, reason: 'Renewal concession — Q3 pilot', createdBy: 'j.okafor', createdAt: '2026-06-02T09:12:44.000Z', outcome: 'WON' },
    { overrideId: 'ovr_2210', value: { type: 'QUANTITY' as const, amount: 120 }, reason: 'Migration goodwill', createdBy: 's.patel', createdAt: '2026-03-18T16:40:02.000Z', outcome: 'LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT' },
  ],
  grantStep: { applied: true, winner: 'ovr_4471', value: { type: 'QUANTITY' as const, amount: 200 }, note: 'Most generous GRANT (200) beats the plan baseline (50).' },
  holds: [
    { overrideId: 'ovr_7788', value: { type: 'QUANTITY' as const, amount: 0 }, reason: 'Suspended pending billing investigation', createdBy: 'billing-bot', createdAt: '2026-08-01T02:00:00.000Z', outcome: 'WON' },
  ],
  holdStep: { applied: true, winner: 'ovr_7788', value: { type: 'QUANTITY' as const, amount: 0 }, note: 'Most restrictive HOLD (0) caps the result.' },
  result: { value: { type: 'QUANTITY' as const, amount: 0 }, allowed: true, allowedReason: 'NO_OFF_VALUE_DECLARED' as const },
}
```

- [ ] **Step 2: Write the MSW request handlers over an in-memory store**

```ts
// src/test/mocks/handlers.ts
import { http, HttpResponse } from 'msw'
import { makeAccount, makeAuditEvents, makeCapabilities, makePlans, RESULT_TRACE } from './fixtures'
import type { AccountDetail, Capability, Plan } from '../../types/domain'

export const db = {
  capabilities: makeCapabilities(),
  plans: makePlans(),
  account: makeAccount(),
  // Accounts created via POST /admin/v1/accounts (Task 16) — kept separate from the seeded
  // `account` fixture so every other task's `db.account` references stay valid unchanged.
  createdAccounts: [] as AccountDetail[],
  audit: makeAuditEvents(),
}

export function resetDb() {
  db.capabilities = makeCapabilities()
  db.plans = makePlans()
  db.account = makeAccount()
  db.createdAccounts = []
  db.audit = makeAuditEvents()
}

function findAccount(external: string): AccountDetail | undefined {
  return external === db.account.account ? db.account : db.createdAccounts.find((a) => a.account === external)
}

function problem(status: number, type: string, detail: string) {
  return HttpResponse.json({ type, title: type, status, detail }, { status })
}

export const handlers = [
  http.get('/admin/v1/meta', () =>
    HttpResponse.json({
      changeVisibleEverywhereWithinSeconds: 60,
      answerReuseMaxSeconds: 10,
      snapshotVersion: 48211,
      capabilityAreas: ['api', 'export', 'reports', 'seats', 'support'],
    }),
  ),

  http.get('/admin/v1/capabilities', ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status') ?? 'ACTIVE'
    const q = url.searchParams.get('q')?.toLowerCase()
    let list = db.capabilities
    if (status !== 'ALL') list = list.filter((c) => c.status === status)
    if (q) list = list.filter((c) => c.key.includes(q) || c.displayName.toLowerCase().includes(q))
    return HttpResponse.json({ capabilities: list, snapshotVersion: 48211 })
  }),

  http.post('/admin/v1/capabilities', async ({ request }) => {
    const body = (await request.json()) as {
      key: string
      displayName: string
      description: string | null
      valueType: Capability['valueType']
      default: Capability['default']
      offValue: Capability['offValue']
      tiers: { tier: string; displayName: string }[] | null
    }
    if (db.capabilities.some((c) => c.key === body.key)) {
      return problem(409, 'entitlement/validation-failed', `Key '${body.key}' already declared.`)
    }
    if (!body.key.includes('.')) {
      return problem(422, 'entitlement/validation-failed', 'Key has no dot, so no area.')
    }
    // Ordinals are assigned here, from declaration order — the client never sends one (see
    // CreateCapabilityInput in Task 5), matching how the real service would number a fresh tier list.
    const created: Capability = {
      key: body.key,
      area: body.key.split('.')[0],
      displayName: body.displayName,
      description: body.description,
      valueType: body.valueType,
      default: body.default,
      offValue: body.offValue,
      tiers: (body.tiers ?? []).map((t, i) => ({ ...t, ordinal: i })),
      status: 'ACTIVE',
    }
    db.capabilities.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.get('/admin/v1/capabilities/:key', ({ params }) => {
    const cap = db.capabilities.find((c) => c.key === params.key)
    if (!cap) return problem(404, 'entitlement/unknown-capability', `No capability '${params.key}'.`)
    return HttpResponse.json(cap)
  }),

  http.patch('/admin/v1/capabilities/:key', async ({ params, request }) => {
    const cap = db.capabilities.find((c) => c.key === params.key)
    if (!cap) return problem(404, 'entitlement/unknown-capability', `No capability '${params.key}'.`)
    const patch = (await request.json()) as Partial<Capability>
    Object.assign(cap, patch)
    return HttpResponse.json(cap)
  }),

  http.post('/admin/v1/capabilities/:key/tiers', async ({ params, request }) => {
    const cap = db.capabilities.find((c) => c.key === params.key)
    if (!cap) return problem(404, 'entitlement/unknown-capability', `No capability '${params.key}'.`)
    const { tier, displayName } = (await request.json()) as { tier: string; displayName: string }
    const nextOrdinal = Math.max(-1, ...cap.tiers.map((t) => t.ordinal)) + 1
    cap.tiers.push({ tier, displayName, ordinal: nextOrdinal })
    return HttpResponse.json(cap)
  }),

  http.post('/admin/v1/capabilities/:key/retire', ({ params }) => {
    const cap = db.capabilities.find((c) => c.key === params.key)
    if (!cap) return problem(404, 'entitlement/unknown-capability', `No capability '${params.key}'.`)
    if (cap.status === 'RETIRED') return problem(409, 'entitlement/validation-failed', 'Already retired.')
    cap.status = 'RETIRED'
    return HttpResponse.json({ ...cap, usage: { plans: ['pro'], liveOverrides: 1 } })
  }),

  http.get('/admin/v1/plans', () => HttpResponse.json({ plans: db.plans, snapshotVersion: 48211 })),

  http.post('/admin/v1/plans', async ({ request }) => {
    const body = (await request.json()) as { key: string; name: string; description?: string }
    const created: Plan = { ...body, status: 'ACTIVE', isDefaultForNewAccounts: false, accountCount: 0, entitlementCount: 0 }
    db.plans.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.get('/admin/v1/plans/:key', ({ params }) => {
    const plan = db.plans.find((p) => p.key === params.key)
    if (!plan) return problem(404, 'entitlement/unknown-capability', `No plan '${params.key}'.`)
    return HttpResponse.json({ ...plan, entitlements: [{ capability: 'reports.monthly', value: { type: 'QUANTITY', amount: 50 } }] })
  }),

  http.post('/admin/v1/plans/:key/entitlements/preview', async ({ params, request }) => {
    const plan = db.plans.find((p) => p.key === params.key)
    if (!plan) return problem(404, 'entitlement/unknown-capability', `No plan '${params.key}'.`)
    const body = (await request.json()) as { set: Record<string, unknown>; unset: string[]; previewAccount?: string }
    const diff = [
      ...Object.entries(body.set).map(([capability, after]) => ({ capability, before: { type: 'QUANTITY', amount: 50 }, after })),
      ...body.unset.map((capability) => ({ capability, before: { type: 'SWITCH', enabled: true }, after: null, note: 'Falls back to the capability default.' })),
    ]
    return HttpResponse.json({
      planKey: plan.key,
      affectedAccountCount: plan.accountCount,
      diff,
      previewAccount: body.previewAccount
        ? {
            account: body.previewAccount,
            effects: [
              {
                capability: 'reports.monthly',
                before: { allowed: true, value: { type: 'QUANTITY', amount: 0 }, trace: RESULT_TRACE },
                after: { allowed: true, value: { type: 'QUANTITY', amount: 0 }, trace: RESULT_TRACE },
                changed: false,
                note: 'No change for this account — a HOLD of 0 caps the result either way.',
              },
            ],
          }
        : undefined,
      previewToken: 'pv_test_token',
    })
  }),

  http.put('/admin/v1/plans/:key/entitlements', async ({ params, request }) => {
    const plan = db.plans.find((p) => p.key === params.key)
    if (!plan) return problem(404, 'entitlement/unknown-capability', `No plan '${params.key}'.`)
    const body = (await request.json()) as { previewToken?: string }
    if (body.previewToken !== 'pv_test_token') return problem(409, 'entitlement/validation-failed', 'Missing or stale preview token.')
    return HttpResponse.json({ planKey: plan.key, affectedAccountCount: plan.accountCount, snapshotVersion: 48212, auditSeq: 90112, changeVisibleEverywhereWithinSeconds: 60 })
  }),

  http.post('/admin/v1/plans/:key/archive', ({ params }) => {
    const plan = db.plans.find((p) => p.key === params.key)
    if (!plan) return problem(404, 'entitlement/unknown-capability', `No plan '${params.key}'.`)
    if (plan.accountCount > 0) return problem(409, 'entitlement/plan-in-use', 'Plan has accounts.')
    if (plan.isDefaultForNewAccounts) return problem(409, 'entitlement/default-plan-required', 'Plan is the default.')
    plan.status = 'ARCHIVED'
    return HttpResponse.json(plan)
  }),

  http.put('/admin/v1/settings/default-plan', async ({ request }) => {
    const { planKey } = (await request.json()) as { planKey: string }
    db.plans.forEach((p) => (p.isDefaultForNewAccounts = p.key === planKey))
    return HttpResponse.json({ planKey })
  }),

  http.get('/admin/v1/accounts', ({ request }) => {
    const url = new URL(request.url)
    const q = url.searchParams.get('q')?.toLowerCase()
    const all = [db.account, ...db.createdAccounts].map((a) => ({ external: a.account, name: a.name, planKey: a.plan.key }))
    const filtered = q ? all.filter((a) => a.external.toLowerCase().includes(q) || a.name?.toLowerCase().includes(q)) : all
    return HttpResponse.json({ accounts: filtered, nextCursor: null })
  }),

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

  http.get('/admin/v1/accounts/:external', ({ params }) => {
    const account = findAccount(String(params.external))
    if (!account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    return HttpResponse.json(account)
  }),

  http.put('/admin/v1/accounts/:external/plan', async ({ params, request }) => {
    if (params.external !== db.account.account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    const body = (await request.json()) as { planKey: string; source: 'PERSON' | 'SYSTEM'; actor: string }
    db.account.plan = { key: body.planKey, name: body.planKey, assignedAt: new Date(0).toISOString(), assignedBy: body.actor, source: body.source }
    return HttpResponse.json({ ...db.account, retainedOverrideCount: db.account.overrides.length })
  }),

  http.post('/admin/v1/accounts/:external/overrides', async ({ params, request }) => {
    if (params.external !== db.account.account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    const body = (await request.json()) as { capability: string; kind: 'GRANT' | 'HOLD'; value: unknown; reason?: string }
    if (!body.reason || body.reason.trim() === '') return problem(422, 'entitlement/reason-required', 'Reason is required.')
    const created = {
      id: `ovr_${Math.floor(Math.random() * 100000)}`,
      capability: body.capability,
      kind: body.kind,
      value: body.value as never,
      reason: body.reason,
      createdBy: 'dev-operator',
      createdAt: new Date(0).toISOString(),
      effectNow: 'WINNING' as const,
    }
    db.account.overrides.push(created)
    return HttpResponse.json({ override: created, decision: { allowed: true, value: body.value, trace: RESULT_TRACE }, snapshotVersion: 48212, changeVisibleEverywhereWithinSeconds: 60 }, { status: 201 })
  }),

  http.delete('/admin/v1/accounts/:external/overrides/:id', ({ params }) => {
    if (params.external !== db.account.account) return problem(404, 'entitlement/unknown-account', `No account '${params.external}'.`)
    db.account.overrides = db.account.overrides.filter((o) => o.id !== params.id)
    return HttpResponse.json({ decision: { allowed: true, value: { type: 'QUANTITY', amount: 50 }, trace: RESULT_TRACE }, snapshotVersion: 48212 })
  }),

  http.get('/admin/v1/check', ({ request }) => {
    const url = new URL(request.url)
    let account = url.searchParams.get('account')
    let capability = url.searchParams.get('capability')
    const overrideRef = url.searchParams.get('override')
    if (overrideRef) {
      const owner = [db.account, ...db.createdAccounts].find((a) => a.overrides.some((o) => o.id === overrideRef))
      const found = owner?.overrides.find((o) => o.id === overrideRef)
      if (!owner || !found) return problem(404, 'entitlement/unknown-capability', `No override '${overrideRef}'.`)
      account = owner.account
      capability = found.capability
    }
    const foundAccount = findAccount(String(account))
    if (!foundAccount) return problem(404, 'entitlement/unknown-account', `No account '${account}'.`)
    const cap = db.capabilities.find((c) => c.key === capability)
    if (!cap) return problem(404, 'entitlement/unknown-capability', `No capability '${capability}'.`)
    if (cap.status === 'RETIRED') return problem(409, 'entitlement/retired-capability', `Capability '${capability}' is retired.`)
    return HttpResponse.json({
      account, capability, allowed: true, value: { type: 'QUANTITY', amount: 0 },
      snapshotVersion: 48211, evaluatedAt: '2026-08-09T14:03:11.482Z', trace: RESULT_TRACE,
    })
  }),

  http.get('/admin/v1/audit', ({ request }) => {
    const url = new URL(request.url)
    const account = url.searchParams.get('account')
    const planKey = url.searchParams.get('planKey')
    const actor = url.searchParams.get('actor')
    const entityType = url.searchParams.get('entityType')
    let events = db.audit
    if (account) events = events.filter((e) => e.account === account)
    if (planKey) events = events.filter((e) => e.planKey === planKey)
    if (actor) events = events.filter((e) => e.actor.id === actor)
    if (entityType) events = events.filter((e) => e.entityType === entityType)
    return HttpResponse.json({ events, nextCursor: null })
  }),
]
```

- [ ] **Step 3: Wire the MSW node server and hook it into Vitest**

```ts
// src/test/mocks/server.ts
import { setupServer } from 'msw/node'
import { handlers, resetDb } from './handlers'

export const server = setupServer(...handlers)
export { resetDb }
```

```ts
// src/test/setup.ts  (replaces Task 1's version)
import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server, resetDb } from './mocks/server'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetDb()
})
afterAll(() => server.close())
```

- [ ] **Step 4: Write the query key factory**

```ts
// src/queries/keys.ts
export const queryKeys = {
  meta: ['meta'] as const,
  capabilities: (params?: { area?: string; q?: string; status?: string }) => ['capabilities', params ?? {}] as const,
  capability: (key: string) => ['capabilities', key] as const,
  plans: () => ['plans'] as const,
  plan: (key: string) => ['plans', key] as const,
  accounts: (params?: { q?: string; planKey?: string; cursor?: string }) => ['accounts', params ?? {}] as const,
  account: (external: string) => ['accounts', external] as const,
  check: (params: { account?: string; capability?: string; override?: string }) => ['check', params] as const,
  audit: (params: Record<string, string | undefined>) => ['audit', params] as const,
}
```

- [ ] **Step 5: Write the render helper used by every component/screen test**

Every screen from Task 10 onward renders `<Link>` and/or calls `useParams`/`useNavigate`/`useRouterState` — all of which throw when there is no `<RouterProvider>` ancestor (`useRouter()` returns `null` outside one, and the first hook that dereferences it crashes with `Cannot read properties of null`). So this helper mounts a real, minimal `RouterProvider` — a memory-history router with exactly one route, at `/`, whose component is the `ui` under test — rather than only wrapping in `QueryClientProvider`. This is the same `createRootRoute`/`createRoute`/`createMemoryHistory` shape the app's own `src/router.tsx` uses, just with a single throwaway route instead of the real tree, so it composes with every future task's `Link`/`useParams` usage with no per-test boilerplate.

```tsx
// src/test/testUtils.tsx
import type { ReactElement } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from '@tanstack/react-router'

export function renderWithProviders(ui: ReactElement, options?: { initialPath?: string }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  const rootRoute = createRootRoute()
  const testRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: () => ui })
  const router = createRouter({
    routeTree: rootRoute.addChildren([testRoute]),
    history: createMemoryHistory({ initialEntries: [options?.initialPath ?? '/'] }),
  })

  const result = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { queryClient, router, ...result }
}
```

The `router` returned alongside the RTL render result lets a test assert on `router.state.location.pathname` after a simulated navigation, or pass `{ initialPath: '/accounts/acct_9931' }` if a future test needs the mounted location to be something other than `/` (no task in this plan currently needs that, but the option costs nothing to have).

- [ ] **Step 6: Confirm the toolchain wires together**

```bash
cd management/frontend/management-ui && npm test
```

Expected: PASS — the existing `value.test.ts` suite still passes; no new tests were added in this task (fixtures/handlers are exercised starting in Task 4), but the run must complete without MSW throwing "server not found" or import errors.

- [ ] **Step 7: Commit**

```bash
git add management/frontend/management-ui/src/test/mocks/fixtures.ts \
        management/frontend/management-ui/src/test/mocks/handlers.ts \
        management/frontend/management-ui/src/test/mocks/server.ts \
        management/frontend/management-ui/src/test/testUtils.tsx \
        management/frontend/management-ui/src/test/setup.ts \
        management/frontend/management-ui/src/queries/keys.ts
git commit -m "frontend: MSW fixtures/handlers, render helper, query key factory"
```

---

### Task 4: API core — typed fetch wrapper, error model, service metadata

**Files:**
- Create: `management/frontend/management-ui/src/api/http.ts`
- Create: `management/frontend/management-ui/src/api/http.test.ts`
- Create: `management/frontend/management-ui/src/api/meta.ts`
- Create: `management/frontend/management-ui/src/api/meta.test.ts`

**Interfaces:**
- Consumes: MSW `server`/`resetDb` from Task 3, `queryKeys.meta`.
- Produces: `ApiError` (class, carries `.problem: ProblemDetails`), `apiGet/apiPost/apiPatch/apiPut/apiDelete<T>(path, body?) => Promise<T>` from `http.ts`; `ServiceMeta` type and `getMeta() => Promise<ServiceMeta>` from `meta.ts`. Every later `api/*.ts` module (Task 5) imports only `apiGet`/`apiPost`/`apiPatch`/`apiPut`/`apiDelete`/`ApiError` from `http.ts` — none constructs its own `fetch` call.

- [ ] **Step 1: Write the failing tests for the http wrapper**

```ts
// src/api/http.test.ts
import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/mocks/server'
import { apiGet, apiPost, ApiError } from './http'

describe('apiGet', () => {
  it('returns parsed JSON on success', async () => {
    server.use(http.get('/admin/v1/ping', () => HttpResponse.json({ ok: true })))
    await expect(apiGet<{ ok: boolean }>('/ping')).resolves.toEqual({ ok: true })
  })

  it('throws an ApiError carrying the problem+json body on failure', async () => {
    server.use(
      http.get('/admin/v1/boom', () =>
        HttpResponse.json(
          { type: 'entitlement/unknown-account', title: 'Unknown account', status: 404, detail: 'No such account.' },
          { status: 404 },
        ),
      ),
    )
    await expect(apiGet('/boom')).rejects.toMatchObject(
      new ApiError({ type: 'entitlement/unknown-account', title: 'Unknown account', status: 404, detail: 'No such account.' }),
    )
  })
})

describe('apiPost', () => {
  it('sends a JSON body and returns the parsed response', async () => {
    server.use(
      http.post('/admin/v1/echo', async ({ request }) => HttpResponse.json(await request.json())),
    )
    await expect(apiPost<{ x: number }>('/echo', { x: 1 })).resolves.toEqual({ x: 1 })
  })
})
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/api/http.test.ts
```

Expected: FAIL — `./http` has no exported members.

- [ ] **Step 3: Implement `src/api/http.ts`**

```ts
// src/api/http.ts
export interface ProblemDetails {
  type: string
  title: string
  status: number
  detail?: string
  instance?: string
  [key: string]: unknown
}

export class ApiError extends Error {
  readonly problem: ProblemDetails
  constructor(problem: ProblemDetails) {
    super(problem.detail ?? problem.title)
    this.problem = problem
  }
}

const BASE = '/admin/v1'

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!res.ok) {
    const problem = (await res.json().catch(
      () => ({ type: 'entitlement/unknown', title: res.statusText, status: res.status }) satisfies ProblemDetails,
    )) as ProblemDetails
    throw new ApiError(problem)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export function apiGet<T>(path: string): Promise<T> {
  return apiFetch<T>(path)
}

export function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
}

export function apiPatch<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'PATCH', body: JSON.stringify(body) })
}

export function apiPut<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'PUT', body: JSON.stringify(body) })
}

export function apiDelete<T>(path: string, body?: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) })
}
```

- [ ] **Step 4: Run to confirm the http tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/api/http.test.ts
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing test for `meta.ts`**

```ts
// src/api/meta.test.ts
import { describe, expect, it } from 'vitest'
import { getMeta } from './meta'

describe('getMeta', () => {
  it('returns the service metadata used for the liveness promise', async () => {
    await expect(getMeta()).resolves.toEqual({
      changeVisibleEverywhereWithinSeconds: 60,
      answerReuseMaxSeconds: 10,
      snapshotVersion: 48211,
      capabilityAreas: ['api', 'export', 'reports', 'seats', 'support'],
    })
  })
})
```

- [ ] **Step 6: Implement `src/api/meta.ts`**

```ts
// src/api/meta.ts
import { apiGet } from './http'

export interface ServiceMeta {
  changeVisibleEverywhereWithinSeconds: number
  answerReuseMaxSeconds: number
  snapshotVersion: number
  capabilityAreas: string[]
}

export function getMeta(): Promise<ServiceMeta> {
  return apiGet<ServiceMeta>('/meta')
}
```

- [ ] **Step 7: Run both files' tests to confirm all pass**

```bash
cd management/frontend/management-ui && npx vitest run src/api
```

Expected: PASS, 4 tests total.

- [ ] **Step 8: Commit**

```bash
git add management/frontend/management-ui/src/api/http.ts management/frontend/management-ui/src/api/http.test.ts \
        management/frontend/management-ui/src/api/meta.ts management/frontend/management-ui/src/api/meta.test.ts
git commit -m "frontend: typed fetch wrapper, RFC 9457 error model, service metadata"
```

---

### Task 5: API resource modules — capabilities, plans, accounts, checker, audit

**Files:**
- Create: `management/frontend/management-ui/src/api/capabilities.ts` + `.test.ts`
- Create: `management/frontend/management-ui/src/api/plans.ts` + `.test.ts`
- Create: `management/frontend/management-ui/src/api/accounts.ts` + `.test.ts`
- Create: `management/frontend/management-ui/src/api/checker.ts` + `.test.ts`
- Create: `management/frontend/management-ui/src/api/audit.ts` + `.test.ts`

**Interfaces:**
- Consumes: `apiGet/apiPost/apiPatch/apiPut/apiDelete` from `./http` (Task 4); `Capability`, `Plan`, `PlanEntitlementDiffEntry`, `AccountDetail`, `AccountSummary`, `Override`, `Decision`, `AuditEvent` from `../types/domain` (Task 2); MSW fixtures/handlers from Task 3.
- Produces every function screen tasks (10–20) call directly — their exact names and signatures below are load-bearing for those tasks:
  - `listCapabilities(params?) => Promise<{capabilities: Capability[]; snapshotVersion: number}>`
  - `getCapability(key) => Promise<Capability>`
  - `createCapability(input) => Promise<Capability>`
  - `updateCapability(key, patch) => Promise<Capability>`
  - `addCapabilityTier(key, tier) => Promise<Capability>`
  - `retireCapability(key) => Promise<Capability & {usage: {plans: string[]; liveOverrides: number}}>`
  - `listPlans() => Promise<{plans: Plan[]; snapshotVersion: number}>`
  - `createPlan(input) => Promise<Plan>`
  - `getPlan(key) => Promise<Plan & {entitlements: {capability:string; value: unknown}[]}>`
  - `previewPlanEntitlements(key, input) => Promise<PlanPreviewResult>`
  - `applyPlanEntitlements(key, input) => Promise<PlanApplyResult>`
  - `archivePlan(key) => Promise<Plan>`
  - `setDefaultPlan(planKey) => Promise<{planKey: string}>`
  - `listAccounts(params?) => Promise<{accounts: AccountSummary[]; nextCursor: string|null}>`
  - `createAccount(input: {external: string; name?: string}) => Promise<AccountDetail>`
  - `getAccount(external) => Promise<AccountDetail>`
  - `setAccountPlan(external, input) => Promise<AccountDetail & {retainedOverrideCount: number}>`
  - `addOverride(external, input) => Promise<{override: Override; decision: Decision; snapshotVersion: number; changeVisibleEverywhereWithinSeconds: number}>`
  - `removeOverride(external, id, reason?) => Promise<{decision: Decision; snapshotVersion: number}>`
  - `checkDecision(params) => Promise<Decision>`
  - `listAuditEvents(params) => Promise<{events: AuditEvent[]; nextCursor: string|null}>` (every call site passes an object — pass `{}` for "no filters", never omit the argument)

- [ ] **Step 1: Write and run the failing capabilities tests, then implement**

```ts
// src/api/capabilities.test.ts
import { describe, expect, it } from 'vitest'
import { listCapabilities, getCapability, createCapability, retireCapability } from './capabilities'
import { ApiError } from './http'

describe('capabilities API', () => {
  it('lists active capabilities by default', async () => {
    const { capabilities } = await listCapabilities()
    expect(capabilities.map((c) => c.key)).toContain('reports.monthly')
  })

  it('gets one capability by key', async () => {
    const cap = await getCapability('support')
    expect(cap.tiers.map((t) => t.tier)).toEqual(['community', 'standard', 'gold'])
  })

  it('rejects an unknown capability with entitlement/unknown-capability', async () => {
    await expect(getCapability('nope.nope')).rejects.toSatisfy(
      (e) => e instanceof ApiError && e.problem.type === 'entitlement/unknown-capability',
    )
  })

  it('creates a capability', async () => {
    const created = await createCapability({
      key: 'integration.salesforce', displayName: 'Salesforce integration', description: null,
      valueType: 'SWITCH', default: { type: 'SWITCH', enabled: false }, offValue: null, tiers: [],
    })
    expect(created.area).toBe('integration')
  })

  it('retires a capability and reports usage', async () => {
    const retired = await retireCapability('reports.monthly')
    expect(retired.status).toBe('RETIRED')
    expect(retired.usage.liveOverrides).toBe(1)
  })
})
```

Run `npx vitest run src/api/capabilities.test.ts` to confirm it fails, then implement:

```ts
// src/api/capabilities.ts
import { apiGet, apiPost, apiPatch } from './http'
import type { Capability, CapabilityTier } from '../types/domain'

export function listCapabilities(params?: { area?: string; q?: string; status?: 'ACTIVE' | 'RETIRED' | 'ALL' }) {
  const search = new URLSearchParams()
  if (params?.area) search.set('area', params.area)
  if (params?.q) search.set('q', params.q)
  if (params?.status) search.set('status', params.status)
  const qs = search.toString()
  return apiGet<{ capabilities: Capability[]; snapshotVersion: number }>(`/capabilities${qs ? `?${qs}` : ''}`)
}

export function getCapability(key: string) {
  return apiGet<Capability>(`/capabilities/${key}`)
}

export interface CreateCapabilityInput {
  key: string
  displayName: string
  description: string | null
  valueType: Capability['valueType']
  default: Capability['default']
  offValue: Capability['offValue']
  tiers: { tier: string; displayName: string }[] | null
}

export function createCapability(input: CreateCapabilityInput) {
  return apiPost<Capability>('/capabilities', input)
}

export function updateCapability(key: string, patch: Partial<Pick<Capability, 'displayName' | 'description' | 'default' | 'offValue'>>) {
  return apiPatch<Capability>(`/capabilities/${key}`, patch)
}

export function addCapabilityTier(key: string, tier: { tier: string; displayName: string }) {
  return apiPost<Capability>(`/capabilities/${key}/tiers`, tier)
}

export function retireCapability(key: string) {
  return apiPost<Capability & { usage: { plans: string[]; liveOverrides: number } }>(`/capabilities/${key}/retire`)
}

export type { CapabilityTier }
```

- [ ] **Step 2: Write and run the failing plans tests, then implement**

```ts
// src/api/plans.test.ts
import { describe, expect, it } from 'vitest'
import { listPlans, previewPlanEntitlements, applyPlanEntitlements, archivePlan } from './plans'
import { ApiError } from './http'

describe('plans API', () => {
  it('lists plans with account counts and the default marker', async () => {
    const { plans } = await listPlans()
    expect(plans.find((p) => p.key === 'free')?.isDefaultForNewAccounts).toBe(true)
  })

  it('previews an entitlement change with the affected count and a diff', async () => {
    const preview = await previewPlanEntitlements('pro', {
      set: { 'reports.monthly': { type: 'QUANTITY', amount: 75 } }, unset: [], previewAccount: 'acct_9931',
    })
    expect(preview.affectedAccountCount).toBe(26890)
    expect(preview.diff[0]).toMatchObject({ capability: 'reports.monthly' })
    expect(preview.previewAccount?.effects[0].changed).toBe(false)
  })

  it('rejects applying without a valid preview token', async () => {
    await expect(
      applyPlanEntitlements('pro', { set: {}, unset: [], previewToken: 'stale' }),
    ).rejects.toSatisfy((e) => e instanceof ApiError && e.problem.status === 409)
  })

  it('refuses to archive a plan with accounts on it', async () => {
    await expect(archivePlan('pro')).rejects.toSatisfy(
      (e) => e instanceof ApiError && e.problem.type === 'entitlement/plan-in-use',
    )
  })
})
```

Implement:

```ts
// src/api/plans.ts
import { apiGet, apiPost, apiPut } from './http'
import type { EntitlementValue } from '../types/value'
import type { Plan, PlanEntitlementDiffEntry, Trace } from '../types/domain'

export function listPlans() {
  return apiGet<{ plans: Plan[]; snapshotVersion: number }>('/plans')
}

export function createPlan(input: { key: string; name: string; description?: string }) {
  return apiPost<Plan>('/plans', input)
}

export function getPlan(key: string) {
  return apiGet<Plan & { entitlements: { capability: string; value: EntitlementValue }[] }>(`/plans/${key}`)
}

export interface PlanEntitlementEditInput {
  set: Record<string, EntitlementValue>
  unset: string[]
  previewAccount?: string
}

export interface PlanPreviewResult {
  planKey: string
  affectedAccountCount: number
  diff: PlanEntitlementDiffEntry[]
  previewAccount?: {
    account: string
    effects: {
      capability: string
      before: { allowed: boolean; value: EntitlementValue; trace: Trace }
      after: { allowed: boolean; value: EntitlementValue; trace: Trace }
      changed: boolean
      note?: string
    }[]
  }
  previewToken: string
}

export function previewPlanEntitlements(key: string, input: PlanEntitlementEditInput) {
  return apiPost<PlanPreviewResult>(`/plans/${key}/entitlements/preview`, input)
}

export interface PlanApplyResult {
  planKey: string
  affectedAccountCount: number
  snapshotVersion: number
  auditSeq: number
  changeVisibleEverywhereWithinSeconds: number
}

export function applyPlanEntitlements(key: string, input: { set: Record<string, EntitlementValue>; unset: string[]; previewToken: string }) {
  return apiPut<PlanApplyResult>(`/plans/${key}/entitlements`, input)
}

export function archivePlan(key: string) {
  return apiPost<Plan>(`/plans/${key}/archive`)
}

export function setDefaultPlan(planKey: string) {
  return apiPut<{ planKey: string }>('/settings/default-plan', { planKey })
}
```

- [ ] **Step 3: Write and run the failing accounts tests, then implement**

```ts
// src/api/accounts.test.ts
import { describe, expect, it } from 'vitest'
import { listAccounts, getAccount, addOverride, removeOverride, setAccountPlan } from './accounts'
import { ApiError } from './http'

describe('accounts API', () => {
  it('gets an account with entitlements and overrides', async () => {
    const account = await getAccount('acct_9931')
    expect(account.plan.key).toBe('pro')
    expect(account.overrides).toHaveLength(2)
  })

  it('searches accounts by query', async () => {
    const { accounts } = await listAccounts({ q: 'northwind' })
    expect(accounts).toHaveLength(1)
  })

  it('rejects an override with no reason', async () => {
    await expect(
      addOverride('acct_9931', { capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 10 }, reason: '' }),
    ).rejects.toSatisfy((e) => e instanceof ApiError && e.problem.type === 'entitlement/reason-required')
  })

  it('creates and then removes an override', async () => {
    const created = await addOverride('acct_9931', {
      capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 10 }, reason: 'Test grant',
    })
    await expect(removeOverride('acct_9931', created.override.id)).resolves.toMatchObject({ snapshotVersion: 48212 })
  })

  it('reassigns the plan and reports retained overrides', async () => {
    const result = await setAccountPlan('acct_9931', { planKey: 'free', source: 'PERSON', actor: 'a.reyes', reason: 'Downgrade' })
    expect(result.retainedOverrideCount).toBe(2)
  })
})
```

Implement:

```ts
// src/api/accounts.ts
import { apiGet, apiPost, apiPut, apiDelete } from './http'
import type { EntitlementValue } from '../types/value'
import type { AccountDetail, AccountSummary, AssignmentSource, Decision, Override, OverrideKind } from '../types/domain'

export function listAccounts(params?: { q?: string; planKey?: string; cursor?: string }) {
  const search = new URLSearchParams()
  if (params?.q) search.set('q', params.q)
  if (params?.planKey) search.set('planKey', params.planKey)
  if (params?.cursor) search.set('cursor', params.cursor)
  const qs = search.toString()
  return apiGet<{ accounts: AccountSummary[]; nextCursor: string | null }>(`/accounts${qs ? `?${qs}` : ''}`)
}

export function createAccount(input: { external: string; name?: string }) {
  return apiPost<AccountDetail>('/accounts', input)
}

export function getAccount(external: string) {
  return apiGet<AccountDetail>(`/accounts/${external}`)
}

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

- [ ] **Step 4: Write and run the failing checker test, then implement**

```ts
// src/api/checker.test.ts
import { describe, expect, it } from 'vitest'
import { checkDecision } from './checker'

describe('checker API', () => {
  it('returns a decision with a full trace', async () => {
    const decision = await checkDecision({ account: 'acct_9931', capability: 'reports.monthly' })
    expect(decision.trace.result.allowed).toBe(true)
    expect(decision.trace.grants).toHaveLength(2)
  })
})
```

Implement:

```ts
// src/api/checker.ts
import { apiGet } from './http'
import type { Decision } from '../types/domain'

export function checkDecision(params: { account: string; capability?: string; override?: string }) {
  const search = new URLSearchParams()
  search.set('account', params.account)
  if (params.capability) search.set('capability', params.capability)
  if (params.override) search.set('override', params.override)
  return apiGet<Decision>(`/check?${search.toString()}`)
}
```

- [ ] **Step 5: Write and run the failing audit test, then implement**

```ts
// src/api/audit.test.ts
import { describe, expect, it } from 'vitest'
import { listAuditEvents } from './audit'

describe('audit API', () => {
  it('lists audit events newest first', async () => {
    const { events } = await listAuditEvents({})
    expect(events.map((e) => e.seq)).toEqual([90114, 90113, 90112])
  })

  it('records the affected-account count on a plan-entitlement edit', async () => {
    const { events } = await listAuditEvents({})
    expect(events.find((e) => e.entityType === 'PLAN_ENTITLEMENT')?.affectedAccountCount).toBe(26890)
  })
})
```

Implement:

```ts
// src/api/audit.ts
import { apiGet } from './http'
import type { AuditEvent } from '../types/domain'

export interface AuditQuery {
  account?: string
  planKey?: string
  actor?: string
  entityType?: string
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

export function listAuditEvents(params: AuditQuery) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined) search.set(k, String(v))
  })
  const qs = search.toString()
  return apiGet<{ events: AuditEvent[]; nextCursor: string | null }>(`/audit${qs ? `?${qs}` : ''}`)
}
```

- [ ] **Step 6: Run the full API suite**

```bash
cd management/frontend/management-ui && npx vitest run src/api
```

Expected: PASS, all capability/plan/account/checker/audit tests plus Task 4's tests green.

- [ ] **Step 7: Commit**

```bash
git add management/frontend/management-ui/src/api/capabilities.ts management/frontend/management-ui/src/api/capabilities.test.ts \
        management/frontend/management-ui/src/api/plans.ts management/frontend/management-ui/src/api/plans.test.ts \
        management/frontend/management-ui/src/api/accounts.ts management/frontend/management-ui/src/api/accounts.test.ts \
        management/frontend/management-ui/src/api/checker.ts management/frontend/management-ui/src/api/checker.test.ts \
        management/frontend/management-ui/src/api/audit.ts management/frontend/management-ui/src/api/audit.test.ts
git commit -m "frontend: capabilities/plans/accounts/checker/audit API resource modules"
```

---

## Phase 1 — App shell and shared components

### Task 6: Router, app layout, unauthenticated banner, liveness promise

**Files:**
- Create: `management/frontend/management-ui/src/router.tsx`
- Create: `management/frontend/management-ui/src/components/AppLayout.tsx`
- Create: `management/frontend/management-ui/src/components/SaveConfirmation.tsx`
- Create: `management/frontend/management-ui/src/components/SaveConfirmation.test.tsx`
- Create: `management/frontend/management-ui/src/routes/home/HomeRoute.tsx`
- Modify: `management/frontend/management-ui/src/main.tsx`

**Interfaces:**
- Consumes: `getMeta` from `../api/meta`, `queryKeys.meta` from `../queries/keys`.
- Produces: `router` (the `createRouter` instance every screen task registers a route on — later tasks edit `router.tsx` by adding an import, a `createRoute`, and appending it to `rootRoute`'s children array, always shown as the full replacement file content in this plan so there is no ambiguity about the diff); `<SaveConfirmation seconds={n} />` (used verbatim by every mutation success state from Task 10 onward, so its rendered text is defined exactly once).

- [ ] **Step 1: Write the failing test for the liveness-promise component**

```tsx
// src/components/SaveConfirmation.test.tsx
import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/testUtils'
import { SaveConfirmation } from './SaveConfirmation'

describe('SaveConfirmation', () => {
  it('states the exact liveness promise with the given second count', () => {
    renderWithProviders(<SaveConfirmation seconds={60} />)
    expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Confirm it fails, then implement**

```bash
cd management/frontend/management-ui && npx vitest run src/components/SaveConfirmation.test.tsx
```

```tsx
// src/components/SaveConfirmation.tsx
export function SaveConfirmation({ seconds }: { seconds: number }) {
  return <p className="sv-tag" role="status">Saved. Active everywhere within {seconds} seconds.</p>
}
```

Run again to confirm PASS.

- [ ] **Step 3: Write the app layout**

```tsx
// src/components/AppLayout.tsx
import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { getMeta } from '../api/meta'
import { queryKeys } from '../queries/keys'

const NAV_ITEMS = [
  { to: '/capabilities', label: 'Capabilities' },
  { to: '/plans', label: 'Plans' },
  { to: '/accounts', label: 'Accounts' },
  { to: '/checker', label: 'Checker' },
  { to: '/history', label: 'History' },
] as const

export function AppLayout() {
  const meta = useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })
  const location = useRouterState({ select: (s) => s.location })

  return (
    <div className="app-shell">
      <header className="app-topbar">Entitlement Service — Operator Console</header>
      <div className="app-banner">
        Unauthenticated instance — all actions are open and audited as <code>dev-operator</code>.
        {meta.data ? ` Snapshot v${meta.data.snapshotVersion}.` : null}
      </div>
      <nav className="app-navbar">
        {NAV_ITEMS.map((item) => (
          <Link key={item.to} to={item.to} className={location.pathname.startsWith(item.to) ? 'active' : undefined}>
            {item.label}
          </Link>
        ))}
      </nav>
      <main className="app-canvas">
        <Outlet />
      </main>
    </div>
  )
}
```

- [ ] **Step 4: Write the home route**

```tsx
// src/routes/home/HomeRoute.tsx
import { Link } from '@tanstack/react-router'

export function HomeRoute() {
  return (
    <div className="app-panel">
      <h1 className="app-page-title">Entitlement Service</h1>
      <p>Answers, for any account and any capability, whether it is allowed, what the value is, and how that was decided.</p>
      <ul>
        <li><Link to="/capabilities" className="sv-link">Capability registry</Link> — declare and retire capabilities.</li>
        <li><Link to="/plans" className="sv-link">Plans</Link> — edit baseline entitlements for every account on a plan.</li>
        <li><Link to="/accounts" className="sv-link">Accounts</Link> — view an account's effective entitlements and overrides.</li>
        <li><Link to="/checker" className="sv-link">Checker</Link> — see the decision and full explanation for any account and capability.</li>
        <li><Link to="/history" className="sv-link">Change history</Link> — every change, filterable by account, plan and actor.</li>
      </ul>
    </div>
  )
}
```

- [ ] **Step 5: Build the route tree**

```tsx
// src/router.tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })

const routeTree = rootRoute.addChildren([indexRoute])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

(Tasks 10, 12, 13, 15, 17, 19, 20 each replace this file's content to add their own route(s) to `routeTree`'s children array and, where applicable, a `$key`/`$external`-parameterised child route. Each such task shows the complete resulting file.)

- [ ] **Step 6: Wire `main.tsx` to the router and query client**

```tsx
// src/main.tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import { router } from './router'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 10_000, retry: false } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
```

- [ ] **Step 7: Verify the app builds and boots**

```bash
cd management/frontend/management-ui && npm run build && npm test
```

Expected: build succeeds; `SaveConfirmation.test.tsx` and all prior tests still pass. Then start the dev server and confirm the home route renders with working nav links:

```bash
npm run dev -- --host 0.0.0.0 &
sleep 2 && curl -s http://127.0.0.1:5173/ | grep -o '<title>[^<]*</title>'
```

Expected: `<title>management-ui</title>` (confirms the server responds; the SPA shell itself renders client-side). Stop the background dev server afterward (`kill %1` or the job's PID) — it is not meant to stay running as part of this task.

- [ ] **Step 8: Commit**

```bash
git add management/frontend/management-ui/src/router.tsx \
        management/frontend/management-ui/src/components/AppLayout.tsx \
        management/frontend/management-ui/src/components/SaveConfirmation.tsx \
        management/frontend/management-ui/src/components/SaveConfirmation.test.tsx \
        management/frontend/management-ui/src/routes/home/HomeRoute.tsx \
        management/frontend/management-ui/src/main.tsx
git commit -m "frontend: router, app layout, unauthenticated banner, liveness promise component"
```

---

### Task 7: `CapabilityTree` — grouped, collapsible, searchable capability list (c40)

**Files:**
- Create: `management/frontend/management-ui/src/components/CapabilityTree.tsx`
- Create: `management/frontend/management-ui/src/components/CapabilityTree.test.tsx`

**Interfaces:**
- Produces: `<CapabilityTree items={T[]} renderRow={(item: T) => ReactNode} emptyMessage?={string} />`, generic over any `T extends { key: string; area: string; displayName: string }`. Screen 1 (Task 10) instantiates it with `Capability[]`; Screen 2's plan editor (Task 13) instantiates it with a `{ key; area; displayName; planValue: EntitlementValue | null }[]` view model. No other component groups-by-area independently — this is the single implementation criterion c40 depends on for both screens.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/components/CapabilityTree.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { render } from '@testing-library/react'
import { CapabilityTree } from './CapabilityTree'

interface Row { key: string; area: string; displayName: string }

const ROWS: Row[] = [
  { key: 'reports.monthly', area: 'reports', displayName: 'Monthly reports' },
  { key: 'api.access', area: 'api', displayName: 'API access' },
  { key: 'residency.eu', area: 'residency', displayName: 'EU residency' },
  { key: 'residency.us', area: 'residency', displayName: 'US residency' },
]

describe('CapabilityTree', () => {
  it('groups rows under their area heading', () => {
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} />)
    expect(screen.getByRole('heading', { name: 'residency' })).toBeInTheDocument()
    const residencyGroup = screen.getByTestId('capability-group-residency')
    expect(within(residencyGroup).getByText('EU residency')).toBeInTheDocument()
    expect(within(residencyGroup).getByText('US residency')).toBeInTheDocument()
  })

  it('collapses and re-expands a group on click', async () => {
    const user = userEvent.setup()
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} />)
    await user.click(screen.getByRole('button', { name: /residency/i }))
    expect(screen.queryByText('EU residency')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /residency/i }))
    expect(screen.getByText('EU residency')).toBeInTheDocument()
  })

  it('filters rows by search text across key and display name', async () => {
    const user = userEvent.setup()
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} />)
    await user.type(screen.getByPlaceholderText('Search capabilities'), 'monthly')
    expect(screen.getByText('Monthly reports')).toBeInTheDocument()
    expect(screen.queryByText('API access')).not.toBeInTheDocument()
  })

  it('renders the empty message when no rows match', async () => {
    const user = userEvent.setup()
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} emptyMessage="No capabilities found." />)
    await user.type(screen.getByPlaceholderText('Search capabilities'), 'zzz-no-match')
    expect(screen.getByText('No capabilities found.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/components/CapabilityTree.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/components/CapabilityTree.tsx
import { useMemo, useState, type ReactNode } from 'react'

interface CapabilityTreeItem {
  key: string
  area: string
  displayName: string
}

export interface CapabilityTreeProps<T extends CapabilityTreeItem> {
  items: T[]
  renderRow: (item: T) => ReactNode
  emptyMessage?: string
}

export function CapabilityTree<T extends CapabilityTreeItem>({ items, renderRow, emptyMessage }: CapabilityTreeProps<T>) {
  const [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return items
    return items.filter((item) => item.key.toLowerCase().includes(q) || item.displayName.toLowerCase().includes(q))
  }, [items, query])

  const groups = useMemo(() => {
    const byArea = new Map<string, T[]>()
    for (const item of filtered) {
      const bucket = byArea.get(item.area) ?? []
      bucket.push(item)
      byArea.set(item.area, bucket)
    }
    return [...byArea.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [filtered])

  return (
    <div className="capability-tree">
      <input
        className="sv-field"
        type="search"
        placeholder="Search capabilities"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        aria-label="Search capabilities"
      />
      {groups.length === 0 ? (
        <p>{emptyMessage ?? 'No capabilities found.'}</p>
      ) : (
        groups.map(([area, rows]) => {
          const isCollapsed = collapsed[area] ?? false
          return (
            <section key={area} data-testid={`capability-group-${area}`}>
              <h3>
                <button
                  type="button"
                  aria-expanded={!isCollapsed}
                  onClick={() => setCollapsed((prev) => ({ ...prev, [area]: !isCollapsed }))}
                >
                  {area} ({rows.length})
                </button>
              </h3>
              {!isCollapsed && <ul>{rows.map((row) => <li key={row.key}>{renderRow(row)}</li>)}</ul>}
            </section>
          )
        })
      )}
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/components/CapabilityTree.test.tsx
```

Expected: PASS, 4 tests. (Note: the `heading` role query in the first test matches the `<h3>` around the toggle `<button>` — Testing Library's accessible-name computation for a heading containing a button uses the button's text, which includes the row count; use `screen.getByRole('heading', { name: /residency/i })` if the exact-string match above is brittle against the `(2)` suffix — adjust the test to a regex match during Step 1 if the exact match fails.)

- [ ] **Step 5: Commit**

```bash
git add management/frontend/management-ui/src/components/CapabilityTree.tsx \
        management/frontend/management-ui/src/components/CapabilityTree.test.tsx
git commit -m "frontend: CapabilityTree — grouped, collapsible, searchable capability list"
```

---

### Task 8: `ValueEditor` and `ValueBadge` — per-value-type input and read-only display

**Files:**
- Create: `management/frontend/management-ui/src/components/ValueEditor.tsx`
- Create: `management/frontend/management-ui/src/components/ValueEditor.test.tsx`

**Interfaces:**
- Consumes: `EntitlementValue`, `ValueType`, `formatValue` from `../types/value`; `CapabilityTier` from `../types/domain`.
- Produces: `<ValueEditor valueType={ValueType} tiers={CapabilityTier[]} value={EntitlementValue} onChange={(v: EntitlementValue) => void} />` (used by every create/edit form from Task 11 onward) and `<ValueBadge value={EntitlementValue} tiers={CapabilityTier[]} />` (a thin wrapper around `formatValue` used by every read-only table cell from Task 10 onward, rendered as `<span className="sv-tag">…</span>`).

- [ ] **Step 1: Write the failing tests**

```tsx
// src/components/ValueEditor.test.tsx
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ValueEditor, ValueBadge } from './ValueEditor'
import type { CapabilityTier } from '../types/domain'

const TIERS: CapabilityTier[] = [
  { tier: 'community', ordinal: 0, displayName: 'Community' },
  { tier: 'gold', ordinal: 1, displayName: 'Gold' },
]

describe('ValueEditor', () => {
  it('toggles a SWITCH value', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ValueEditor valueType="SWITCH" tiers={[]} value={{ type: 'SWITCH', enabled: false }} onChange={onChange} />)
    await user.click(screen.getByRole('checkbox', { name: 'Enabled' }))
    expect(onChange).toHaveBeenCalledWith({ type: 'SWITCH', enabled: true })
  })

  it('edits a bounded QUANTITY amount', () => {
    // fireEvent.change, not userEvent.type: ValueEditor's `value` prop is a static test
    // fixture here (the spy `onChange` never feeds a new value back in), so it stays a
    // controlled input pinned at amount:50 across renders. userEvent.type() dispatches
    // one keystroke event per character against that unchanging controlled value, which
    // does not accumulate the way it would against a real, wired-up parent — e.g. typing
    // "75" after clearing observably produces amount:505, not amount:75. A single
    // fireEvent.change sets the whole field's value in one atomic event, side-stepping
    // the mismatch entirely, which is exactly what this test needs: proof that ValueEditor
    // reports the field's value on change, not a simulation of realistic per-keystroke UX.
    const onChange = vi.fn()
    render(<ValueEditor valueType="QUANTITY" tiers={[]} value={{ type: 'QUANTITY', amount: 50 }} onChange={onChange} />)
    const input = screen.getByRole('spinbutton', { name: 'Amount' })
    fireEvent.change(input, { target: { value: '75' } })
    expect(onChange).toHaveBeenCalledWith({ type: 'QUANTITY', amount: 75 })
  })

  it('switches a QUANTITY to unlimited via the checkbox, disabling the amount field', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ValueEditor valueType="QUANTITY" tiers={[]} value={{ type: 'QUANTITY', amount: 50 }} onChange={onChange} />)
    await user.click(screen.getByRole('checkbox', { name: 'Unlimited' }))
    expect(onChange).toHaveBeenCalledWith({ type: 'QUANTITY', unlimited: true })
  })

  it('picks a TIER from the declared list', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ValueEditor valueType="TIER" tiers={TIERS} value={{ type: 'TIER', tier: 'community' }} onChange={onChange} />)
    await user.selectOptions(screen.getByRole('combobox', { name: 'Tier' }), 'gold')
    expect(onChange).toHaveBeenCalledWith({ type: 'TIER', tier: 'gold' })
  })
})

describe('ValueBadge', () => {
  it('renders unlimited as a word, not a number', () => {
    render(<ValueBadge value={{ type: 'QUANTITY', unlimited: true }} tiers={[]} />)
    expect(screen.getByText('Unlimited')).toBeInTheDocument()
  })

  it('renders a tier by its declared display name', () => {
    render(<ValueBadge value={{ type: 'TIER', tier: 'gold' }} tiers={TIERS} />)
    expect(screen.getByText('Gold')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/components/ValueEditor.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/components/ValueEditor.tsx
import { formatValue } from '../types/value'
import type { EntitlementValue, ValueType } from '../types/value'
import type { CapabilityTier } from '../types/domain'

export interface ValueEditorProps {
  valueType: ValueType
  tiers: CapabilityTier[]
  value: EntitlementValue
  onChange: (value: EntitlementValue) => void
}

export function ValueEditor({ valueType, tiers, value, onChange }: ValueEditorProps) {
  if (valueType === 'SWITCH') {
    const enabled = value.type === 'SWITCH' && value.enabled
    return (
      <label className="sv-label">
        <input type="checkbox" checked={enabled} onChange={(e) => onChange({ type: 'SWITCH', enabled: e.target.checked })} />
        {' '}Enabled
      </label>
    )
  }

  if (valueType === 'QUANTITY') {
    const amount = value.type === 'QUANTITY' && 'amount' in value ? value.amount : 0
    const unlimited = value.type === 'QUANTITY' && 'unlimited' in value && value.unlimited === true
    return (
      <div>
        <label className="sv-label">
          Amount
          <input
            className="sv-field"
            type="number"
            min={0}
            disabled={unlimited}
            value={amount}
            onChange={(e) => onChange({ type: 'QUANTITY', amount: Number(e.target.value) })}
          />
        </label>
        <label className="sv-label">
          <input
            type="checkbox"
            checked={Boolean(unlimited)}
            onChange={(e) => onChange(e.target.checked ? { type: 'QUANTITY', unlimited: true } : { type: 'QUANTITY', amount: 0 })}
          />
          {' '}Unlimited
        </label>
      </div>
    )
  }

  const currentTier = value.type === 'TIER' ? value.tier : ''
  return (
    <label className="sv-label">
      Tier
      <select className="sv-field" value={currentTier} onChange={(e) => onChange({ type: 'TIER', tier: e.target.value })}>
        <option value="" disabled>Select a tier</option>
        {tiers.map((t) => <option key={t.tier} value={t.tier}>{t.displayName}</option>)}
      </select>
    </label>
  )
}

export function ValueBadge({ value, tiers }: { value: EntitlementValue; tiers: CapabilityTier[] }) {
  return <span className="sv-tag">{formatValue(value, tiers)}</span>
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/components/ValueEditor.test.tsx
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add management/frontend/management-ui/src/components/ValueEditor.tsx \
        management/frontend/management-ui/src/components/ValueEditor.test.tsx
git commit -m "frontend: ValueEditor and ValueBadge for SWITCH/QUANTITY/TIER values"
```

---

### Task 9: `TraceView` — the four-step explanation (c21–c24)

**Files:**
- Create: `management/frontend/management-ui/src/components/TraceView.tsx`
- Create: `management/frontend/management-ui/src/components/TraceView.test.tsx`

**Interfaces:**
- Consumes: `Trace` from `../types/domain`; `ValueBadge` from `./ValueEditor`.
- Produces: `<TraceView trace={Trace} tiers?={CapabilityTier[]} />` — used verbatim by the Plan preview (Task 15), Account override responses (Task 18) and the Checker screen (Task 19). It performs no resolution and no re-derivation: every string it prints comes from a field already on `trace`, per `ui-screens.md` "Trace rendering".

- [ ] **Step 1: Write the failing tests, using the exact fixture trace from `contracts/decision-api.md`**

```tsx
// src/components/TraceView.test.tsx
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TraceView } from './TraceView'
import { RESULT_TRACE } from '../test/mocks/fixtures'

describe('TraceView', () => {
  it('names the baseline source and the plan that set it', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByText(/Plan 'pro' sets this capability\./)).toBeInTheDocument()
  })

  it('shows every GRANT, winners and losers alike, with reason/author/date', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByText('Renewal concession — Q3 pilot')).toBeInTheDocument()
    expect(screen.getByText('Migration goodwill')).toBeInTheDocument()
    expect(screen.getByText('j.okafor')).toBeInTheDocument()
  })

  it('marks the winning grant and the losing grant distinctly', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('grant-ovr_4471')).toHaveTextContent('WON')
    expect(screen.getByTestId('grant-ovr_2210')).toHaveTextContent('LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT')
  })

  it('shows the winning HOLD', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('hold-ovr_7788')).toHaveTextContent('WON')
  })

  it('renders the result value and allowed flag', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('trace-result')).toHaveTextContent('0')
    expect(screen.getByTestId('trace-result')).toHaveTextContent('allowed: true')
  })

  it('prints denial by absence as a line, not an empty region, when no GRANTs exist', () => {
    const noGrants = {
      ...RESULT_TRACE,
      grants: [],
      grantStep: { applied: false, why: 'NO_GRANTS' },
    }
    render(<TraceView trace={noGrants} />)
    expect(screen.getByText('No GRANTs exist.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/components/TraceView.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/components/TraceView.tsx
import { ValueBadge } from './ValueEditor'
import type { Trace, TraceCandidate } from '../types/domain'
import type { CapabilityTier } from '../types/domain'

function CandidateRow({ candidate, testIdPrefix }: { candidate: TraceCandidate; testIdPrefix: string }) {
  return (
    <li data-testid={`${testIdPrefix}-${candidate.overrideId}`}>
      <ValueBadge value={candidate.value} tiers={[]} /> {candidate.overrideId} — {candidate.reason} — {candidate.createdBy}, {candidate.createdAt}{' '}
      <strong>{candidate.outcome}</strong>
    </li>
  )
}

const GRANT_ABSENCE_TEXT: Record<string, string> = {
  NO_GRANTS: 'No GRANTs exist.',
  PLAN_AT_LEAST_AS_GENEROUS: 'The plan is at least as generous as every GRANT, so the plan stands.',
}

const HOLD_ABSENCE_TEXT: Record<string, string> = {
  NO_HOLDS: 'No HOLDs exist.',
  HOLD_NOT_MORE_RESTRICTIVE: 'No HOLD is more restrictive than the result, so nothing was capped.',
}

export function TraceView({ trace, tiers = [] }: { trace: Trace; tiers?: CapabilityTier[] }) {
  return (
    <div className="trace-view">
      <section>
        <h4>Baseline</h4>
        <p>
          <ValueBadge value={trace.baseline.value} tiers={tiers} /> {trace.baseline.note}
        </p>
      </section>

      <section>
        <h4>Grants</h4>
        {trace.grants.length > 0 ? (
          <ul>{trace.grants.map((g) => <CandidateRow key={g.overrideId} candidate={g} testIdPrefix="grant" />)}</ul>
        ) : (
          <p>{GRANT_ABSENCE_TEXT[trace.grantStep.why ?? 'NO_GRANTS']}</p>
        )}
        {trace.grantStep.note && <p>{trace.grantStep.note}</p>}
      </section>

      <section>
        <h4>Holds</h4>
        {trace.holds.length > 0 ? (
          <ul>{trace.holds.map((h) => <CandidateRow key={h.overrideId} candidate={h} testIdPrefix="hold" />)}</ul>
        ) : (
          <p>{HOLD_ABSENCE_TEXT[trace.holdStep.why ?? 'NO_HOLDS']}</p>
        )}
        {trace.holdStep.note && <p>{trace.holdStep.note}</p>}
      </section>

      <section data-testid="trace-result">
        <h4>Result</h4>
        <p>
          <ValueBadge value={trace.result.value} tiers={tiers} /> · allowed: {String(trace.result.allowed)}
        </p>
      </section>
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/components/TraceView.test.tsx
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add management/frontend/management-ui/src/components/TraceView.tsx \
        management/frontend/management-ui/src/components/TraceView.test.tsx
git commit -m "frontend: TraceView — renders the four-step decision explanation"
```

---

## Phase 2 — Screen 1: Capability registry (`/capabilities`)

### Task 10: Capability list route — tree, search, status filter, show-retired toggle (c40)

**Files:**
- Create: `management/frontend/management-ui/src/routes/capabilities/CapabilitiesListRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/capabilities/CapabilitiesListRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `listCapabilities` from `../../api/capabilities`; `CapabilityTree` from `../../components/CapabilityTree`; `ValueBadge` from `../../components/ValueEditor`; `Link` from `@tanstack/react-router`.
- Produces: the `/capabilities` route, registered on `router`. Task 12's detail route links back here; this task does not depend on Task 12.

- [ ] **Step 1: Write the failing test**

```tsx
// src/routes/capabilities/CapabilitiesListRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CapabilitiesListRoute } from './CapabilitiesListRoute'
import { db } from '../../test/mocks/handlers'

describe('CapabilitiesListRoute', () => {
  it('lists active capabilities grouped by area', async () => {
    renderWithProviders(<CapabilitiesListRoute />)
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
    expect(screen.queryByText('reports.monthly retired')).not.toBeInTheDocument()
  })

  it('hides retired capabilities until "show retired" is checked', async () => {
    db.capabilities[0].status = 'RETIRED'
    const user = userEvent.setup()
    renderWithProviders(<CapabilitiesListRoute />)
    await waitFor(() => expect(screen.queryByText('API access')).toBeInTheDocument())
    expect(screen.queryByText('Monthly reports')).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Show retired' }))
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/capabilities/CapabilitiesListRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/capabilities/CapabilitiesListRoute.tsx
import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { listCapabilities } from '../../api/capabilities'
import { queryKeys } from '../../queries/keys'
import { CapabilityTree } from '../../components/CapabilityTree'
import { ValueBadge } from '../../components/ValueEditor'
import { CapabilityCreateForm } from './CapabilityCreateForm'

export function CapabilitiesListRoute() {
  const [showRetired, setShowRetired] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const status = showRetired ? 'ALL' : 'ACTIVE'
  const query = useQuery({
    queryKey: queryKeys.capabilities({ status }),
    queryFn: () => listCapabilities({ status }),
  })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Capabilities</h1>
      <label className="sv-label">
        <input type="checkbox" checked={showRetired} onChange={(e) => setShowRetired(e.target.checked)} />
        {' '}Show retired
      </label>
      <button type="button" className="sv-btn" onClick={() => setShowCreate((v) => !v)}>
        {showCreate ? 'Cancel' : 'Declare a capability'}
      </button>
      {showCreate && <CapabilityCreateForm onCreated={() => setShowCreate(false)} />}
      {query.data && (
        <CapabilityTree
          items={query.data.capabilities}
          emptyMessage="No capabilities found."
          renderRow={(cap) => (
            <Link to="/capabilities/$key" params={{ key: cap.key }} className="sv-link" style={cap.status === 'RETIRED' ? { opacity: 0.5 } : undefined}>
              {cap.displayName} <ValueBadge value={cap.default} tiers={cap.tiers} /> {cap.status === 'RETIRED' && '(retired)'}
            </Link>
          )}
        />
      )}
    </div>
  )
}
```

Note: `CapabilityCreateForm` is created in Task 11. This task's own test suite (Step 1) never opens the create panel, so it does not depend on Task 11's file existing being correct — but the file **must exist** for this module to import successfully. Create it now as the minimal-but-real form below (Task 11 replaces it with the full version); this is not a placeholder because it is what the module needs to compile and this exact minimal form is superseded, not stubbed, by Task 11:

```tsx
// src/routes/capabilities/CapabilityCreateForm.tsx (superseded by Task 11)
export function CapabilityCreateForm(_props: { onCreated: () => void }) {
  return <p>Capability creation form.</p>
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/capabilities/CapabilitiesListRoute.test.tsx
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Register the route**

```tsx
// src/router.tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
// AppLayout's nav links to all five top-level screens, and TanStack Router's typed `Link to=`
// only accepts paths registered in this tree — so every screen not yet built keeps the
// Task 6 placeholder here until the task that implements it replaces this file wholesale.
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: () => <div>Plans</div> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: () => <div>Accounts</div> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([indexRoute, capabilitiesRoute, plansRoute, accountsRoute, checkerRoute, historyRoute])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/routes/capabilities/CapabilitiesListRoute.tsx \
        management/frontend/management-ui/src/routes/capabilities/CapabilitiesListRoute.test.tsx \
        management/frontend/management-ui/src/routes/capabilities/CapabilityCreateForm.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: capability registry list route (c40)"
```

---

### Task 11: Capability create form — §5 rules made visible (c1, c2, c3)

**Files:**
- Modify: `management/frontend/management-ui/src/routes/capabilities/CapabilityCreateForm.tsx` (replaces Task 10's minimal version)
- Create: `management/frontend/management-ui/src/routes/capabilities/CapabilityCreateForm.test.tsx`

**Interfaces:**
- Consumes: `createCapability`, `CreateCapabilityInput` from `../../api/capabilities`; `ValueEditor` from `../../components/ValueEditor`; `zeroValueFor` from `../../types/value`; `getMeta` from `../../api/meta`; `SaveConfirmation` from `../../components/SaveConfirmation`.
- Produces: `<CapabilityCreateForm onCreated={() => void} />`, unchanged signature from Task 10 so `CapabilitiesListRoute` needs no edit.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/capabilities/CapabilityCreateForm.test.tsx
import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CapabilityCreateForm } from './CapabilityCreateForm'
import { getCapability } from '../../api/capabilities'

describe('CapabilityCreateForm', () => {
  it('hides the off-value control for SWITCH, per §5', async () => {
    renderWithProviders(<CapabilityCreateForm onCreated={vi.fn()} />)
    expect(screen.queryByLabelText(/off-value/i)).not.toBeInTheDocument()
  })

  it('constrains the off-value to 0 for QUANTITY, offered as a checkbox not a number field', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityCreateForm onCreated={vi.fn()} />)
    await user.selectOptions(screen.getByRole('combobox', { name: 'Value type' }), 'QUANTITY')
    expect(screen.getByRole('checkbox', { name: /off at 0/i })).toBeInTheDocument()
    expect(screen.queryByRole('spinbutton', { name: /off-value/i })).not.toBeInTheDocument()
  })

  it('requires at least two tiers before submit is enabled for a TIER capability', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityCreateForm onCreated={vi.fn()} />)
    await user.type(screen.getByLabelText('Key'), 'sla.level')
    await user.type(screen.getByLabelText('Display name'), 'SLA level')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Value type' }), 'TIER')
    expect(screen.getByRole('button', { name: 'Declare capability' })).toBeDisabled()
    await user.type(screen.getAllByLabelText('Tier key')[0], 'none')
    await user.type(screen.getAllByLabelText('Tier display name')[0], 'None')
    expect(screen.getByRole('button', { name: 'Declare capability' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Add tier' }))
    await user.type(screen.getAllByLabelText('Tier key')[1], 'standard')
    await user.type(screen.getAllByLabelText('Tier display name')[1], 'Standard')
    expect(screen.getByRole('button', { name: 'Declare capability' })).toBeEnabled()
  })

  it('creates a SWITCH capability and reports the liveness promise', async () => {
    const user = userEvent.setup()
    const onCreated = vi.fn()
    renderWithProviders(<CapabilityCreateForm onCreated={onCreated} />)
    await user.type(screen.getByLabelText('Key'), 'integration.hubspot')
    await user.type(screen.getByLabelText('Display name'), 'HubSpot integration')
    await user.click(screen.getByRole('button', { name: 'Declare capability' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    expect(onCreated).toHaveBeenCalled()
    await expect(getCapability('integration.hubspot')).resolves.toMatchObject({ valueType: 'SWITCH' })
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/capabilities/CapabilityCreateForm.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/capabilities/CapabilityCreateForm.tsx
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createCapability } from '../../api/capabilities'
import type { CreateCapabilityInput } from '../../api/capabilities'
import { getMeta } from '../../api/meta'
import { queryKeys } from '../../queries/keys'
import { ValueEditor } from '../../components/ValueEditor'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import { zeroValueFor } from '../../types/value'
import type { EntitlementValue, ValueType } from '../../types/value'

interface TierDraft { tier: string; displayName: string }

export function CapabilityCreateForm({ onCreated }: { onCreated: () => void }) {
  const [key, setKey] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [description, setDescription] = useState('')
  const [valueType, setValueType] = useState<ValueType>('SWITCH')
  const [defaultValue, setDefaultValue] = useState<EntitlementValue>(zeroValueFor('SWITCH'))
  const [offAtZero, setOffAtZero] = useState(false)
  const [offTier, setOffTier] = useState('')
  const [tierDrafts, setTierDrafts] = useState<TierDraft[]>([{ tier: '', displayName: '' }, { tier: '', displayName: '' }])

  const queryClient = useQueryClient()
  const meta = useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })
  const mutation = useMutation({
    mutationFn: (input: CreateCapabilityInput) => createCapability(input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['capabilities'] })
      onCreated()
    },
  })

  function changeValueType(next: ValueType) {
    setValueType(next)
    setDefaultValue(zeroValueFor(next))
    setOffAtZero(false)
    setOffTier('')
  }

  const validTiers = tierDrafts.filter((t) => t.tier.trim() && t.displayName.trim())
  const canSubmitTiers = valueType !== 'TIER' || validTiers.length >= 2
  const canSubmit = key.trim() !== '' && displayName.trim() !== '' && canSubmitTiers && !mutation.isPending

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const offValue: EntitlementValue | null =
      valueType === 'QUANTITY' && offAtZero ? { type: 'QUANTITY', amount: 0 }
      : valueType === 'TIER' && offTier ? { type: 'TIER', tier: offTier }
      : null
    mutation.mutate({
      key, displayName, description: description || null, valueType, default: defaultValue, offValue,
      tiers: valueType === 'TIER' ? validTiers : null,
    })
  }

  return (
    <form className="app-panel" onSubmit={handleSubmit}>
      <label className="sv-label">Key
        <input className="sv-field" id="cap-key" value={key} onChange={(e) => setKey(e.target.value)} aria-label="Key" />
      </label>
      <label className="sv-label">Display name
        <input className="sv-field" value={displayName} onChange={(e) => setDisplayName(e.target.value)} aria-label="Display name" />
      </label>
      <label className="sv-label">Description
        <textarea className="sv-field" value={description} onChange={(e) => setDescription(e.target.value)} aria-label="Description" />
      </label>
      <label className="sv-label">Value type
        <select className="sv-field" value={valueType} onChange={(e) => changeValueType(e.target.value as ValueType)} aria-label="Value type">
          <option value="SWITCH">Switch</option>
          <option value="QUANTITY">Quantity</option>
          <option value="TIER">Tier</option>
        </select>
      </label>

      <fieldset>
        <legend>Default value</legend>
        <ValueEditor
          valueType={valueType}
          tiers={validTiers.map((t, i) => ({ ...t, ordinal: i }))}
          value={defaultValue}
          onChange={setDefaultValue}
        />
      </fieldset>

      {valueType === 'QUANTITY' && (
        <label className="sv-label">
          <input type="checkbox" checked={offAtZero} onChange={(e) => setOffAtZero(e.target.checked)} aria-label="Off at 0" />
          {' '}Off at 0
        </label>
      )}
      {valueType === 'TIER' && (
        <label className="sv-label">Off-value tier (optional)
          <select className="sv-field" value={offTier} onChange={(e) => setOffTier(e.target.value)} aria-label="Off-value tier">
            <option value="">None</option>
            {validTiers.map((t) => <option key={t.tier} value={t.tier}>{t.displayName}</option>)}
          </select>
        </label>
      )}
      {/* SWITCH declares no off-value control at all — its off is false, inherently and always (§5) */}

      {valueType === 'TIER' && (
        <fieldset>
          <legend>Tiers (at least two, ascending)</legend>
          {tierDrafts.map((draft, i) => (
            <div key={i}>
              <input
                className="sv-field" aria-label="Tier key" placeholder="tier key" value={draft.tier}
                onChange={(e) => setTierDrafts((prev) => prev.map((d, idx) => (idx === i ? { ...d, tier: e.target.value } : d)))}
              />
              <input
                className="sv-field" aria-label="Tier display name" placeholder="Display name" value={draft.displayName}
                onChange={(e) => setTierDrafts((prev) => prev.map((d, idx) => (idx === i ? { ...d, displayName: e.target.value } : d)))}
              />
            </div>
          ))}
          <button type="button" className="sv-btn--secondary" onClick={() => setTierDrafts((prev) => [...prev, { tier: '', displayName: '' }])}>
            Add tier
          </button>
        </fieldset>
      )}

      <button type="submit" className="sv-btn" disabled={!canSubmit}>Declare capability</button>
      {mutation.isSuccess && meta.data && <SaveConfirmation seconds={meta.data.changeVisibleEverywhereWithinSeconds} />}
      {mutation.isError && <p className="sv-tag" style={{ color: 'var(--sv-danger)' }}>{(mutation.error as Error).message}</p>}
    </form>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/capabilities/CapabilityCreateForm.test.tsx
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Re-run the Task 10 list route test to confirm no regression**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/capabilities
```

Expected: PASS, 6 tests total.

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/routes/capabilities/CapabilityCreateForm.tsx \
        management/frontend/management-ui/src/routes/capabilities/CapabilityCreateForm.test.tsx
git commit -m "frontend: capability create form — off-value and tier rules made visible (c1-c3)"
```

---

### Task 12: Capability detail route — edit, append a tier, retire (c1, c3, c8)

**Files:**
- Create: `management/frontend/management-ui/src/routes/capabilities/CapabilityDetailRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/capabilities/CapabilityDetailRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `getCapability`, `updateCapability`, `addCapabilityTier`, `retireCapability` from `../../api/capabilities`; `ValueEditor` from `../../components/ValueEditor`; `SaveConfirmation`; `useParams` from `@tanstack/react-router`.
- Produces: the `/capabilities/$key` route, linked from Task 10's list.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/capabilities/CapabilityDetailRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CapabilityDetailRoute } from './CapabilityDetailRoute'
import { getCapability } from '../../api/capabilities'

describe('CapabilityDetailRoute', () => {
  it('shows the value type as read-only with an explanatory title', async () => {
    renderWithProviders(<CapabilityDetailRoute capabilityKey="support" />)
    await waitFor(() => expect(screen.getByText('Support level')).toBeInTheDocument())
    const valueTypeField = screen.getByTestId('value-type-readonly')
    expect(valueTypeField).toHaveTextContent('TIER')
    expect(valueTypeField).toHaveAttribute('title', "A capability has one value type across every plan.")
  })

  it('edits the display name and description', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="api.access" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toHaveValue('API access'))
    await user.clear(screen.getByLabelText('Display name'))
    await user.type(screen.getByLabelText('Display name'), 'API access (v2)')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    await expect(getCapability('api.access')).resolves.toMatchObject({ displayName: 'API access (v2)' })
  })

  it('appends a tier above the current maximum ordinal, with no reordering control offered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="support" />)
    await waitFor(() => expect(screen.getByText('Gold')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /reorder/i })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('New tier key'), 'platinum')
    await user.type(screen.getByLabelText('New tier display name'), 'Platinum')
    await user.click(screen.getByRole('button', { name: 'Append tier' }))
    await waitFor(() => expect(screen.getByText('Platinum')).toBeInTheDocument())
    await expect(getCapability('support')).resolves.toMatchObject({
      tiers: [
        { tier: 'community', ordinal: 0 }, { tier: 'standard', ordinal: 1 },
        { tier: 'gold', ordinal: 2 }, { tier: 'platinum', ordinal: 3 },
      ],
    })
  })

  it('retires with a confirmation naming usage, and offers no delete control', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="reports.monthly" />)
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retire capability' }))
    expect(screen.getByText(/used by 1 plan/i)).toBeInTheDocument()
    expect(screen.getByText(/permanent/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm retirement' }))
    await waitFor(() => expect(getCapability('reports.monthly')).resolves.toMatchObject({ status: 'RETIRED' }))
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/capabilities/CapabilityDetailRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/capabilities/CapabilityDetailRoute.tsx
import { useEffect, useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getCapability, updateCapability, addCapabilityTier, retireCapability } from '../../api/capabilities'
import { getMeta } from '../../api/meta'
import { queryKeys } from '../../queries/keys'
import { ValueEditor } from '../../components/ValueEditor'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import type { EntitlementValue } from '../../types/value'

export function CapabilityDetailRoute({ capabilityKey }: { capabilityKey?: string } = {}) {
  const params = useParams({ strict: false }) as { key?: string }
  const key = capabilityKey ?? params.key!

  const capability = useQuery({ queryKey: queryKeys.capability(key), queryFn: () => getCapability(key) })
  const meta = useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })
  const queryClient = useQueryClient()

  const [displayName, setDisplayName] = useState('')
  const [description, setDescription] = useState('')
  const [defaultValue, setDefaultValue] = useState<EntitlementValue | null>(null)
  const [newTierKey, setNewTierKey] = useState('')
  const [newTierName, setNewTierName] = useState('')
  const [confirmingRetire, setConfirmingRetire] = useState(false)
  const [usage, setUsage] = useState<{ plans: string[]; liveOverrides: number } | null>(null)

  useEffect(() => {
    if (capability.data) {
      setDisplayName(capability.data.displayName)
      setDescription(capability.data.description ?? '')
      setDefaultValue(capability.data.default)
    }
  }, [capability.data])

  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.capability(key) })

  const saveMutation = useMutation({
    mutationFn: () => updateCapability(key, { displayName, description: description || null, default: defaultValue! }),
    onSuccess: invalidate,
  })
  const tierMutation = useMutation({
    mutationFn: () => addCapabilityTier(key, { tier: newTierKey, displayName: newTierName }),
    onSuccess: () => { invalidate(); setNewTierKey(''); setNewTierName('') },
  })
  const retireMutation = useMutation({ mutationFn: () => retireCapability(key), onSuccess: invalidate })

  if (!capability.data || !defaultValue) return <p>Loading…</p>
  const cap = capability.data

  async function startRetire() {
    const result = await retireCapability(key)
    setUsage(result.usage)
    setConfirmingRetire(true)
  }

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{cap.displayName}</h1>

      <p data-testid="value-type-readonly" title="A capability has one value type across every plan.">{cap.valueType}</p>

      <label className="sv-label">Display name
        <input className="sv-field" value={displayName} onChange={(e) => setDisplayName(e.target.value)} aria-label="Display name" />
      </label>
      <label className="sv-label">Description
        <textarea className="sv-field" value={description} onChange={(e) => setDescription(e.target.value)} aria-label="Description" />
      </label>
      <fieldset>
        <legend>Default value</legend>
        <ValueEditor valueType={cap.valueType} tiers={cap.tiers} value={defaultValue} onChange={setDefaultValue} />
      </fieldset>
      <button type="button" className="sv-btn" onClick={() => saveMutation.mutate()}>Save changes</button>
      {saveMutation.isSuccess && meta.data && <SaveConfirmation seconds={meta.data.changeVisibleEverywhereWithinSeconds} />}

      {cap.valueType === 'TIER' && (
        <fieldset>
          <legend>Tiers</legend>
          <ul>{cap.tiers.map((t) => <li key={t.tier}>{t.displayName}</li>)}</ul>
          <input className="sv-field" aria-label="New tier key" value={newTierKey} onChange={(e) => setNewTierKey(e.target.value)} />
          <input className="sv-field" aria-label="New tier display name" value={newTierName} onChange={(e) => setNewTierName(e.target.value)} />
          <button type="button" className="sv-btn--secondary" onClick={() => tierMutation.mutate()} disabled={!newTierKey || !newTierName}>
            Append tier
          </button>
        </fieldset>
      )}

      {cap.status === 'ACTIVE' && !confirmingRetire && (
        <button type="button" className="sv-btn--secondary" onClick={startRetire}>Retire capability</button>
      )}
      {confirmingRetire && usage && (
        <div className="app-panel">
          <p>Used by {usage.plans.length} plan{usage.plans.length === 1 ? '' : 's'}, {usage.liveOverrides} live overrides.</p>
          <p>Retirement is permanent. This capability stays visible in history.</p>
          <button type="button" className="sv-btn" onClick={() => retireMutation.mutate()}>Confirm retirement</button>
          <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingRetire(false)}>Cancel</button>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/capabilities/CapabilityDetailRoute.test.tsx
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Register the route**

```tsx
// src/router.tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
// Still placeholders — kept so AppLayout's typed nav links to the not-yet-built screens compile.
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: () => <div>Plans</div> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: () => <div>Accounts</div> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, accountsRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/routes/capabilities/CapabilityDetailRoute.tsx \
        management/frontend/management-ui/src/routes/capabilities/CapabilityDetailRoute.test.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: capability detail — edit, append tier, retire (c1, c3, c8)"
```

---

## Phase 3 — Screen 2: Plans (`/plans`)

### Task 13: Plans list — account counts, default marker, archive, designate default (c6, c7)

**Files:**
- Create: `management/frontend/management-ui/src/routes/plans/PlansListRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/plans/PlansListRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `listPlans`, `archivePlan`, `setDefaultPlan` from `../../api/plans`.
- Produces: the `/plans` route.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/plans/PlansListRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { PlansListRoute } from './PlansListRoute'
import { listPlans } from '../../api/plans'

describe('PlansListRoute', () => {
  it('shows account counts and marks the default plan', async () => {
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByText('71204')).toBeInTheDocument())
    expect(screen.getByTestId('plan-row-free')).toHaveTextContent('Default for new accounts')
  })

  it('disables archive with an explanatory tooltip when the account count is non-zero', async () => {
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())
    const archiveButton = screen.getByRole('button', { name: 'Archive pro' })
    expect(archiveButton).toBeDisabled()
    expect(archiveButton).toHaveAttribute('title', expect.stringMatching(/26890 accounts/))
  })

  it('designates a new default plan', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Make pro the default' }))
    await waitFor(async () => {
      const { plans } = await listPlans()
      expect(plans.find((p) => p.key === 'pro')?.isDefaultForNewAccounts).toBe(true)
    })
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/plans/PlansListRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/plans/PlansListRoute.tsx
import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listPlans, archivePlan, setDefaultPlan } from '../../api/plans'
import { queryKeys } from '../../queries/keys'

export function PlansListRoute() {
  const query = useQuery({ queryKey: queryKeys.plans(), queryFn: listPlans })
  const queryClient = useQueryClient()
  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.plans() })
  const archiveMutation = useMutation({ mutationFn: archivePlan, onSuccess: invalidate })
  const defaultMutation = useMutation({ mutationFn: setDefaultPlan, onSuccess: invalidate })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Plans</h1>
      <table>
        <thead><tr><th>Plan</th><th>Accounts</th><th>Default</th><th /></tr></thead>
        <tbody>
          {query.data?.plans.map((plan) => (
            <tr key={plan.key} data-testid={`plan-row-${plan.key}`}>
              <td><Link to="/plans/$key" params={{ key: plan.key }} className="sv-link">{plan.name}</Link></td>
              <td>{plan.accountCount}</td>
              <td>{plan.isDefaultForNewAccounts ? 'Default for new accounts' : (
                <button type="button" className="sv-btn--secondary" onClick={() => defaultMutation.mutate(plan.key)}>
                  {`Make ${plan.key} the default`}
                </button>
              )}</td>
              <td>
                <button
                  type="button"
                  className="sv-btn--secondary"
                  disabled={plan.accountCount > 0 || plan.isDefaultForNewAccounts}
                  title={plan.accountCount > 0 ? `Cannot archive — ${plan.accountCount} accounts are on this plan.` : plan.isDefaultForNewAccounts ? 'Cannot archive the default plan.' : undefined}
                  onClick={() => archiveMutation.mutate(plan.key)}
                >
                  {`Archive ${plan.key}`}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/plans/PlansListRoute.test.tsx
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Register the route** (append to `router.tsx`'s imports and children array from Task 12, keeping the capability routes)

```tsx
// src/router.tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'
import { PlansListRoute } from './routes/plans/PlansListRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
// Still placeholders — kept so AppLayout's typed nav links to the not-yet-built screens compile.
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: () => <div>Accounts</div> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, accountsRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/routes/plans/PlansListRoute.tsx \
        management/frontend/management-ui/src/routes/plans/PlansListRoute.test.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: plans list — account counts, archive gating, default designation (c6, c7)"
```

---

### Task 14: Plan editor — capability tree with partial-plan fallback made visible (c4, c40)

**Files:**
- Create: `management/frontend/management-ui/src/routes/plans/PlanEditorRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/plans/PlanEditorRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `getPlan` from `../../api/plans`; `listCapabilities` from `../../api/capabilities`; `CapabilityTree`; `ValueEditor`, `ValueBadge`; `formatValue` from `../../types/value`.
- Produces: `<PlanEditorRoute planKey?={string} />` holding **local, unsaved** edit state — `pendingSet: Record<string, EntitlementValue>` and `pendingUnset: Set<string>` — which Task 15 reads by extending this exact file (shown in full there) to add the review/preview/save flow. No network write happens in this task; §8 requires the affected-account count to be computed before any plan write, which Task 15 is what computes it.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/plans/PlanEditorRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { PlanEditorRoute } from './PlanEditorRoute'

describe('PlanEditorRoute', () => {
  it('shows an explicit plan value distinctly from a capability falling back to default', async () => {
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await waitFor(() => expect(screen.getByText('50')).toBeInTheDocument())
    expect(screen.getByText(/not set — falls back to default \(Off\)/)).toBeInTheDocument()
  })

  it('lets an operator set a capability the plan does not currently mention', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await waitFor(() => expect(screen.getByText(/not set — falls back to default \(Off\)/)).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Edit' })[0])
    await user.click(screen.getByRole('checkbox', { name: 'Enabled' }))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.getByText('On')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/plans/PlanEditorRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/plans/PlanEditorRoute.tsx
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { listCapabilities } from '../../api/capabilities'
import { getPlan } from '../../api/plans'
import { queryKeys } from '../../queries/keys'
import { CapabilityTree } from '../../components/CapabilityTree'
import { ValueEditor, ValueBadge } from '../../components/ValueEditor'
import { formatValue } from '../../types/value'
import type { EntitlementValue } from '../../types/value'
import type { Capability } from '../../types/domain'

interface PlanEditorRow {
  key: string
  area: string
  displayName: string
  capability: Capability
  planValue: EntitlementValue | null
}

export function PlanEditorRoute({ planKey }: { planKey?: string } = {}) {
  const params = useParams({ strict: false }) as { key?: string }
  const key = planKey ?? params.key!

  const planQuery = useQuery({ queryKey: queryKeys.plan(key), queryFn: () => getPlan(key) })
  const capabilitiesQuery = useQuery({ queryKey: queryKeys.capabilities({ status: 'ACTIVE' }), queryFn: () => listCapabilities({ status: 'ACTIVE' }) })

  const [pendingSet, setPendingSet] = useState<Record<string, EntitlementValue>>({})
  const [pendingUnset, setPendingUnset] = useState<Set<string>>(new Set())
  const [editing, setEditing] = useState<string | null>(null)

  if (!planQuery.data || !capabilitiesQuery.data) return <p>Loading…</p>

  const originalByCapability = new Map(planQuery.data.entitlements.map((e) => [e.capability, e.value]))

  const rows: PlanEditorRow[] = capabilitiesQuery.data.capabilities.map((cap) => {
    const original = originalByCapability.get(cap.key) ?? null
    const planValue = pendingUnset.has(cap.key) ? null : (pendingSet[cap.key] ?? original)
    return { key: cap.key, area: cap.area, displayName: cap.displayName, capability: cap, planValue }
  })

  function setCapabilityValue(cap: Capability, value: EntitlementValue) {
    setPendingSet((prev) => ({ ...prev, [cap.key]: value }))
    setPendingUnset((prev) => { const next = new Set(prev); next.delete(cap.key); return next })
  }

  function clearCapabilityValue(cap: Capability) {
    setPendingUnset((prev) => new Set(prev).add(cap.key))
    setPendingSet((prev) => { const next = { ...prev }; delete next[cap.key]; return next })
    setEditing(null)
  }

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{planQuery.data.name}</h1>
      <CapabilityTree
        items={rows}
        renderRow={(row) => (
          <div className="plan-editor-row">
            <span>{row.displayName}</span>{' '}
            {editing === row.key ? (
              <>
                <ValueEditor
                  valueType={row.capability.valueType}
                  tiers={row.capability.tiers}
                  value={row.planValue ?? row.capability.default}
                  onChange={(v) => setCapabilityValue(row.capability, v)}
                />
                <button type="button" className="sv-btn--secondary" onClick={() => setEditing(null)}>Done</button>
              </>
            ) : (
              <>
                {row.planValue ? (
                  <ValueBadge value={row.planValue} tiers={row.capability.tiers} />
                ) : (
                  <span className="sv-tag" style={{ opacity: 0.6 }}>
                    not set — falls back to default ({formatValue(row.capability.default, row.capability.tiers)})
                  </span>
                )}
                <button type="button" className="sv-btn--secondary" onClick={() => setEditing(row.key)}>Edit</button>
                {row.planValue && (
                  <button type="button" className="sv-btn--secondary" onClick={() => clearCapabilityValue(row.capability)}>Clear</button>
                )}
              </>
            )}
          </div>
        )}
      />
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/plans/PlanEditorRoute.test.tsx
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Register the route** (full `router.tsx`, extending Task 13's version)

```tsx
// src/router.tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'
import { PlansListRoute } from './routes/plans/PlansListRoute'
import { PlanEditorRoute } from './routes/plans/PlanEditorRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
const planEditorRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute /> })
// Still placeholders — kept so AppLayout's typed nav links to the not-yet-built screens compile.
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: () => <div>Accounts</div> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, planEditorRoute, accountsRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/routes/plans/PlanEditorRoute.tsx \
        management/frontend/management-ui/src/routes/plans/PlanEditorRoute.test.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: plan editor — partial-plan fallback made visible (c4, c40)"
```

---

### Task 15: Plan save flow — review, single-account preview, gated save (c34, c35, c41)

**Files:**
- Modify: `management/frontend/management-ui/src/routes/plans/PlanEditorRoute.tsx` (extends Task 14's file — full replacement shown)
- Modify: `management/frontend/management-ui/src/routes/plans/PlanEditorRoute.test.tsx` (adds these tests to Task 14's file — full replacement shown)

**Interfaces:**
- Consumes: `previewPlanEntitlements`, `applyPlanEntitlements`, `PlanPreviewResult` from `../../api/plans`; `TraceView` from `../../components/TraceView`; `SaveConfirmation`; `getMeta` (used only as a pre-preview fallback — the definitive number for this screen is the preview/apply response's own `changeVisibleEverywhereWithinSeconds`, never `meta`, so the two can never disagree about a save that already has its own count).
- Produces: the complete three-step save act described in `ui-screens.md` Screen 2. No later task depends on this file.

- [ ] **Step 1: Add the failing tests** (full replacement of `PlanEditorRoute.test.tsx`, Task 14's two tests plus these four)

```tsx
// src/routes/plans/PlanEditorRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { PlanEditorRoute } from './PlanEditorRoute'

async function makeAChange(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByText(/not set — falls back to default \(Off\)/)).toBeInTheDocument())
  await user.click(screen.getAllByRole('button', { name: 'Edit' })[0])
  await user.click(screen.getByRole('checkbox', { name: 'Enabled' }))
  await user.click(screen.getByRole('button', { name: 'Done' }))
}

describe('PlanEditorRoute', () => {
  it('shows an explicit plan value distinctly from a capability falling back to default', async () => {
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await waitFor(() => expect(screen.getByText('50')).toBeInTheDocument())
    expect(screen.getByText(/not set — falls back to default \(Off\)/)).toBeInTheDocument()
  })

  it('lets an operator set a capability the plan does not currently mention', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    expect(screen.getByText('On')).toBeInTheDocument()
  })

  it('disables Save until a preview has been fetched', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('shows a non-dismissable affected-account banner and a diff on review', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText('This change affects 26890 accounts.')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /dismiss/i })).not.toBeInTheDocument()
  })

  it('previews the change on one named account and calls out "no change"', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.type(screen.getByLabelText('Preview account'), 'acct_9931')
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText(/No change for this account/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('saves once a preview exists and shows the resulting liveness promise', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.type(screen.getByLabelText('Preview account'), 'acct_9931')
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Confirm the four new tests fail**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/plans/PlanEditorRoute.test.tsx
```

- [ ] **Step 3: Implement** (full replacement of `PlanEditorRoute.tsx`)

```tsx
// src/routes/plans/PlanEditorRoute.tsx
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listCapabilities } from '../../api/capabilities'
import { getPlan, previewPlanEntitlements, applyPlanEntitlements } from '../../api/plans'
import type { PlanPreviewResult } from '../../api/plans'
import { queryKeys } from '../../queries/keys'
import { CapabilityTree } from '../../components/CapabilityTree'
import { ValueEditor, ValueBadge } from '../../components/ValueEditor'
import { TraceView } from '../../components/TraceView'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import { formatValue } from '../../types/value'
import type { EntitlementValue } from '../../types/value'
import type { Capability } from '../../types/domain'

interface PlanEditorRow {
  key: string
  area: string
  displayName: string
  capability: Capability
  planValue: EntitlementValue | null
}

export function PlanEditorRoute({ planKey }: { planKey?: string } = {}) {
  const params = useParams({ strict: false }) as { key?: string }
  const key = planKey ?? params.key!

  const planQuery = useQuery({ queryKey: queryKeys.plan(key), queryFn: () => getPlan(key) })
  const capabilitiesQuery = useQuery({ queryKey: queryKeys.capabilities({ status: 'ACTIVE' }), queryFn: () => listCapabilities({ status: 'ACTIVE' }) })
  const queryClient = useQueryClient()

  const [pendingSet, setPendingSet] = useState<Record<string, EntitlementValue>>({})
  const [pendingUnset, setPendingUnset] = useState<Set<string>>(new Set())
  const [editing, setEditing] = useState<string | null>(null)
  const [previewAccountInput, setPreviewAccountInput] = useState('')
  const [preview, setPreview] = useState<PlanPreviewResult | null>(null)
  const [applyResult, setApplyResult] = useState<{ changeVisibleEverywhereWithinSeconds: number } | null>(null)

  const reviewMutation = useMutation({
    mutationFn: () => previewPlanEntitlements(key, {
      set: pendingSet, unset: [...pendingUnset], previewAccount: previewAccountInput || undefined,
    }),
    onSuccess: setPreview,
  })
  const saveMutation = useMutation({
    mutationFn: () => applyPlanEntitlements(key, { set: pendingSet, unset: [...pendingUnset], previewToken: preview!.previewToken }),
    onSuccess: (result) => {
      setApplyResult(result)
      queryClient.invalidateQueries({ queryKey: queryKeys.plan(key) })
      queryClient.invalidateQueries({ queryKey: queryKeys.plans() })
      setPendingSet({})
      setPendingUnset(new Set())
      setPreview(null)
    },
  })

  if (!planQuery.data || !capabilitiesQuery.data) return <p>Loading…</p>

  const originalByCapability = new Map(planQuery.data.entitlements.map((e) => [e.capability, e.value]))
  const hasPendingChanges = Object.keys(pendingSet).length > 0 || pendingUnset.size > 0

  const rows: PlanEditorRow[] = capabilitiesQuery.data.capabilities.map((cap) => {
    const original = originalByCapability.get(cap.key) ?? null
    const planValue = pendingUnset.has(cap.key) ? null : (pendingSet[cap.key] ?? original)
    return { key: cap.key, area: cap.area, displayName: cap.displayName, capability: cap, planValue }
  })

  function setCapabilityValue(cap: Capability, value: EntitlementValue) {
    setPendingSet((prev) => ({ ...prev, [cap.key]: value }))
    setPendingUnset((prev) => { const next = new Set(prev); next.delete(cap.key); return next })
    setPreview(null)
  }

  function clearCapabilityValue(cap: Capability) {
    setPendingUnset((prev) => new Set(prev).add(cap.key))
    setPendingSet((prev) => { const next = { ...prev }; delete next[cap.key]; return next })
    setEditing(null)
    setPreview(null)
  }

  const canSave = preview !== null && Boolean(preview.previewAccount)

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{planQuery.data.name}</h1>
      <CapabilityTree
        items={rows}
        renderRow={(row) => (
          <div className="plan-editor-row">
            <span>{row.displayName}</span>{' '}
            {editing === row.key ? (
              <>
                <ValueEditor
                  valueType={row.capability.valueType}
                  tiers={row.capability.tiers}
                  value={row.planValue ?? row.capability.default}
                  onChange={(v) => setCapabilityValue(row.capability, v)}
                />
                <button type="button" className="sv-btn--secondary" onClick={() => setEditing(null)}>Done</button>
              </>
            ) : (
              <>
                {row.planValue ? (
                  <ValueBadge value={row.planValue} tiers={row.capability.tiers} />
                ) : (
                  <span className="sv-tag" style={{ opacity: 0.6 }}>
                    not set — falls back to default ({formatValue(row.capability.default, row.capability.tiers)})
                  </span>
                )}
                <button type="button" className="sv-btn--secondary" onClick={() => setEditing(row.key)}>Edit</button>
                {row.planValue && (
                  <button type="button" className="sv-btn--secondary" onClick={() => clearCapabilityValue(row.capability)}>Clear</button>
                )}
              </>
            )}
          </div>
        )}
      />

      {hasPendingChanges && (
        <div className="app-panel">
          <h2>Review and save</h2>
          <label className="sv-label">Preview account
            <input className="sv-field" value={previewAccountInput} onChange={(e) => setPreviewAccountInput(e.target.value)} aria-label="Preview account" />
          </label>
          <button type="button" className="sv-btn--secondary" onClick={() => reviewMutation.mutate()}>Review changes</button>

          {preview && (
            <div>
              <p role="alert">{`This change affects ${preview.affectedAccountCount} accounts.`}</p>
              <ul>
                {preview.diff.map((d) => (
                  <li key={d.capability}>
                    {d.capability}: {d.before ? formatValue(d.before) : '—'} → {d.after ? formatValue(d.after) : '—'}
                    {d.note && ` (${d.note})`}
                  </li>
                ))}
              </ul>
              {preview.previewAccount && (
                <div>
                  <h3>Effect on {preview.previewAccount.account}</h3>
                  {preview.previewAccount.effects.map((effect) => (
                    <div key={effect.capability}>
                      <h4>{effect.capability}</h4>
                      {!effect.changed && <p>{effect.note ?? 'No change for this account.'}</p>}
                      <div>
                        <strong>Before</strong>
                        <TraceView trace={effect.before.trace} />
                      </div>
                      <div>
                        <strong>After</strong>
                        <TraceView trace={effect.after.trace} />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          <button type="button" className="sv-btn" disabled={!canSave} onClick={() => saveMutation.mutate()}>Save</button>
          {applyResult && <SaveConfirmation seconds={applyResult.changeVisibleEverywhereWithinSeconds} />}
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Confirm all six tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/plans
```

Expected: PASS, 9 tests total (6 in `PlanEditorRoute.test.tsx`, 3 in `PlansListRoute.test.tsx`).

- [ ] **Step 5: Commit**

```bash
git add management/frontend/management-ui/src/routes/plans/PlanEditorRoute.tsx \
        management/frontend/management-ui/src/routes/plans/PlanEditorRoute.test.tsx
git commit -m "frontend: plan save flow — affected-count gate, single-account preview (c34, c35, c41)"
```

---

## Phase 4 — Screen 3: Accounts (`/accounts`)

### Task 16: Accounts list, search, and create (c7)

**Files:**
- Modify: `management/frontend/management-ui/src/test/mocks/handlers.ts` (adds `POST /admin/v1/accounts` and a `createdAccounts` store — see the two edits above, already folded into Task 3's listing for a reader working through this document top to bottom)
- Create: `management/frontend/management-ui/src/routes/accounts/AccountsListRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/accounts/AccountsListRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `listAccounts`, `createAccount` from `../../api/accounts`.
- Produces: the `/accounts` route.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/accounts/AccountsListRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { AccountsListRoute } from './AccountsListRoute'

describe('AccountsListRoute', () => {
  it('lists accounts and links to the account detail route', async () => {
    renderWithProviders(<AccountsListRoute />)
    await waitFor(() => expect(screen.getByRole('link', { name: /Northwind Capital/i })).toBeInTheDocument())
  })

  it('searches by account or name', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountsListRoute />)
    await waitFor(() => expect(screen.getByRole('link', { name: /Northwind Capital/i })).toBeInTheDocument())
    await user.type(screen.getByLabelText('Search accounts'), 'no-such-account')
    await waitFor(() => expect(screen.queryByRole('link', { name: /Northwind Capital/i })).not.toBeInTheDocument())
  })

  it('creates a new account, assigned to the default plan', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountsListRoute />)
    await user.type(screen.getByLabelText('New account external id'), 'acct_5001')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    await waitFor(() => expect(screen.getByRole('link', { name: /acct_5001/i })).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/accounts/AccountsListRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/accounts/AccountsListRoute.tsx
import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listAccounts, createAccount } from '../../api/accounts'
import { queryKeys } from '../../queries/keys'

export function AccountsListRoute() {
  const [q, setQ] = useState('')
  const [newExternal, setNewExternal] = useState('')
  const query = useQuery({ queryKey: queryKeys.accounts({ q }), queryFn: () => listAccounts({ q: q || undefined }) })
  const queryClient = useQueryClient()
  const createMutation = useMutation({
    mutationFn: () => createAccount({ external: newExternal }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['accounts'] }); setNewExternal('') },
  })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Accounts</h1>
      <input className="sv-field" aria-label="Search accounts" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by account or name" />
      <ul>
        {query.data?.accounts.map((a) => (
          <li key={a.external}>
            <Link to="/accounts/$external" params={{ external: a.external }} className="sv-link">
              {a.name ? `${a.name} (${a.external})` : a.external}
            </Link>
          </li>
        ))}
      </ul>
      <form onSubmit={(e) => { e.preventDefault(); createMutation.mutate() }}>
        <input className="sv-field" aria-label="New account external id" value={newExternal} onChange={(e) => setNewExternal(e.target.value)} />
        <button type="submit" className="sv-btn" disabled={!newExternal}>Create account</button>
      </form>
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/accounts/AccountsListRoute.test.tsx
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Register the route** (full `router.tsx`, extending Task 14's version)

```tsx
// src/router.tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'
import { PlansListRoute } from './routes/plans/PlansListRoute'
import { PlanEditorRoute } from './routes/plans/PlanEditorRoute'
import { AccountsListRoute } from './routes/accounts/AccountsListRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
const planEditorRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute /> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute })
// Still placeholders — kept so AppLayout's typed nav links to the not-yet-built screens compile.
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, planEditorRoute, accountsRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/test/mocks/handlers.ts \
        management/frontend/management-ui/src/routes/accounts/AccountsListRoute.tsx \
        management/frontend/management-ui/src/routes/accounts/AccountsListRoute.test.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: accounts list, search, and create (c7)"
```

---

### Task 17: Account detail — header, effective entitlements with source, overrides list (c36, c39)

**Files:**
- Create: `management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `getAccount` from `../../api/accounts`; `checkDecision` from `../../api/checker`; `ValueBadge`; `TraceView`; `OverrideEffect` from `../../types/domain`.
- Produces: the `/accounts/$external` route and `<AccountDetailRoute external?={string} />`. Task 18 extends this exact file (shown in full there) to add override add/remove and change-plan.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/accounts/AccountDetailRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { AccountDetailRoute } from './AccountDetailRoute'

describe('AccountDetailRoute', () => {
  it('shows the plan header naming who assigned it and whether a person or a system', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('Pro')).toBeInTheDocument())
    expect(screen.getByText(/billing-sync/)).toBeInTheDocument()
    expect(screen.getByText(/upstream system/)).toBeInTheDocument()
  })

  it('marks each effective entitlement with its source, and shows the override reason inline for a HOLD', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    const row = screen.getByText('reports.monthly').closest('tr')!
    expect(row).toHaveTextContent('HOLD')
    expect(row).toHaveTextContent('Suspended pending billing investigation')
  })

  it('opens the full trace for a capability on request', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Why?' })[0])
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('lists overrides with their current effect in plain language', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText(/Renewal concession/)).toBeInTheDocument())
    expect(screen.getByText(/overridden by a HOLD/)).toBeInTheDocument()
    expect(screen.getByText(/^winning$|— winning$/)).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/accounts/AccountDetailRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/accounts/AccountDetailRoute.tsx
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { getAccount } from '../../api/accounts'
import { checkDecision } from '../../api/checker'
import { queryKeys } from '../../queries/keys'
import { ValueBadge } from '../../components/ValueEditor'
import { TraceView } from '../../components/TraceView'
import type { OverrideEffect } from '../../types/domain'

const EFFECT_LABELS: Record<OverrideEffect, string> = {
  WINNING: 'winning',
  OVERRIDDEN_BY_HOLD: 'overridden by a HOLD',
  SUPERSEDED_BY_GRANT: 'superseded by a larger or newer GRANT',
  SUPERSEDED_BY_STRICTER_HOLD: 'superseded by a stricter or newer HOLD',
  NO_EFFECT_PLAN_MORE_GENEROUS: 'no effect — plan is more generous',
  NO_EFFECT_NOT_MORE_RESTRICTIVE: 'no effect — not more restrictive than the result',
}

export function AccountDetailRoute({ external: externalProp }: { external?: string } = {}) {
  const params = useParams({ strict: false }) as { external?: string }
  const external = externalProp ?? params.external!

  const accountQuery = useQuery({ queryKey: queryKeys.account(external), queryFn: () => getAccount(external) })
  const [openTraceFor, setOpenTraceFor] = useState<string | null>(null)
  const traceQuery = useQuery({
    queryKey: queryKeys.check({ account: external, capability: openTraceFor ?? undefined }),
    queryFn: () => checkDecision({ account: external, capability: openTraceFor! }),
    enabled: openTraceFor !== null,
  })

  if (!accountQuery.data) return <p>Loading…</p>
  const account = accountQuery.data

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{account.name ?? account.account}</h1>
      <dl>
        <dt>Plan</dt><dd>{account.plan.name}</dd>
        <dt>Assigned</dt>
        <dd>{account.plan.assignedAt} by {account.plan.assignedBy} ({account.plan.source === 'PERSON' ? 'a person' : 'an upstream system'})</dd>
      </dl>

      <h2>Effective entitlements</h2>
      <table>
        <thead><tr><th>Capability</th><th>Value</th><th>Source</th><th /></tr></thead>
        <tbody>
          {account.entitlements.map((row) => (
            <tr key={row.capability}>
              <td>{row.capability}</td>
              <td><ValueBadge value={row.value} tiers={[]} /></td>
              <td>{row.source}{row.sourceDetail?.reason ? ` — ${row.sourceDetail.reason}` : ''}</td>
              <td><button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(row.capability)}>Why?</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {openTraceFor && traceQuery.data && (
        <div className="app-panel">
          <h3>{openTraceFor}</h3>
          <TraceView trace={traceQuery.data.trace} />
          <button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(null)}>Close</button>
        </div>
      )}

      <h2>Overrides</h2>
      <ul>
        {account.overrides.map((o) => (
          <li key={o.id}>
            {o.kind} <ValueBadge value={o.value} tiers={[]} /> — {o.reason} — {o.createdBy}, {o.createdAt} — {EFFECT_LABELS[o.effectNow]}
          </li>
        ))}
      </ul>
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/accounts/AccountDetailRoute.test.tsx
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Register the route** (full `router.tsx`, extending Task 16's version)

```tsx
// src/router.tsx
import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'
import { PlansListRoute } from './routes/plans/PlansListRoute'
import { PlanEditorRoute } from './routes/plans/PlanEditorRoute'
import { AccountsListRoute } from './routes/accounts/AccountsListRoute'
import { AccountDetailRoute } from './routes/accounts/AccountDetailRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
const planEditorRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute /> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute })
const accountDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts/$external', component: () => <AccountDetailRoute /> })
// Still placeholders — kept so AppLayout's typed nav links to the not-yet-built screens compile.
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, planEditorRoute,
  accountsRoute, accountDetailRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.tsx \
        management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.test.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: account detail — plan header, source-marked entitlements, overrides (c36, c39)"
```

---

### Task 18: Add/remove override, change plan (c9, c14, c15, c36)

**Files:**
- Modify: `management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.tsx` (extends Task 17's file — full replacement shown)
- Modify: `management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.test.tsx` (adds these tests to Task 17's file — full replacement shown)

**Interfaces:**
- Consumes: `addOverride`, `removeOverride`, `setAccountPlan` from `../../api/accounts`; `listCapabilities` from `../../api/capabilities`; `ValueEditor`; `zeroValueFor`.
- Produces: the complete Screen 3 feature set. No later task depends on this file.

- [ ] **Step 1: Add the failing tests** (full replacement of `AccountDetailRoute.test.tsx`, Task 17's four tests plus these four)

```tsx
// src/routes/accounts/AccountDetailRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { AccountDetailRoute } from './AccountDetailRoute'

describe('AccountDetailRoute', () => {
  it('shows the plan header naming who assigned it and whether a person or a system', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('Pro')).toBeInTheDocument())
    expect(screen.getByText(/billing-sync/)).toBeInTheDocument()
    expect(screen.getByText(/upstream system/)).toBeInTheDocument()
  })

  it('marks each effective entitlement with its source, and shows the override reason inline for a HOLD', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    const row = screen.getByText('reports.monthly').closest('tr')!
    expect(row).toHaveTextContent('HOLD')
    expect(row).toHaveTextContent('Suspended pending billing investigation')
  })

  it('opens the full trace for a capability on request', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Why?' })[0])
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('lists overrides with their current effect in plain language', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText(/Renewal concession/)).toBeInTheDocument())
    expect(screen.getByText(/overridden by a HOLD/)).toBeInTheDocument()
  })

  it('blocks override submission until a reason is given', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    expect(screen.getByRole('button', { name: 'Save override' })).toBeDisabled()
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    expect(screen.getByRole('button', { name: 'Save override' })).toBeEnabled()
  })

  it('creates an override and immediately shows the resulting trace', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    await user.click(screen.getByRole('button', { name: 'Save override' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('removes an override, warning that a HOLD removal is not itself restricted', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2))
    const holdRemoveButton = screen.getByTestId('remove-ovr_7788')
    await user.click(holdRemoveButton)
    expect(screen.getByText(/audited but not restricted/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm removal' }))
    await waitFor(() => expect(screen.queryByTestId('remove-ovr_7788')).not.toBeInTheDocument())
  })

  it('reassigns the plan and confirms how many overrides are retained', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    await user.selectOptions(screen.getByLabelText('New plan'), 'free')
    await user.click(screen.getByLabelText('A person'))
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    await waitFor(() => expect(screen.getByText(/2 overrides are retained/)).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Confirm the four new tests fail**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/accounts/AccountDetailRoute.test.tsx
```

- [ ] **Step 3: Implement** (full replacement of `AccountDetailRoute.tsx`)

```tsx
// src/routes/accounts/AccountDetailRoute.tsx
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getAccount, addOverride, removeOverride, setAccountPlan } from '../../api/accounts'
import { checkDecision } from '../../api/checker'
import { listPlans } from '../../api/plans'
import { listCapabilities } from '../../api/capabilities'
import { queryKeys } from '../../queries/keys'
import { ValueBadge, ValueEditor } from '../../components/ValueEditor'
import { TraceView } from '../../components/TraceView'
import { zeroValueFor } from '../../types/value'
import type { EntitlementValue } from '../../types/value'
import type { AssignmentSource, Decision, OverrideEffect, OverrideKind } from '../../types/domain'

const EFFECT_LABELS: Record<OverrideEffect, string> = {
  WINNING: 'winning',
  OVERRIDDEN_BY_HOLD: 'overridden by a HOLD',
  SUPERSEDED_BY_GRANT: 'superseded by a larger or newer GRANT',
  SUPERSEDED_BY_STRICTER_HOLD: 'superseded by a stricter or newer HOLD',
  NO_EFFECT_PLAN_MORE_GENEROUS: 'no effect — plan is more generous',
  NO_EFFECT_NOT_MORE_RESTRICTIVE: 'no effect — not more restrictive than the result',
}

function groupByArea<T extends { area: string }>(items: T[]): [string, T[]][] {
  const byArea = new Map<string, T[]>()
  for (const item of items) {
    const bucket = byArea.get(item.area) ?? []
    bucket.push(item)
    byArea.set(item.area, bucket)
  }
  return [...byArea.entries()].sort(([a], [b]) => a.localeCompare(b))
}

export function AccountDetailRoute({ external: externalProp }: { external?: string } = {}) {
  const params = useParams({ strict: false }) as { external?: string }
  const external = externalProp ?? params.external!
  const queryClient = useQueryClient()

  const accountQuery = useQuery({ queryKey: queryKeys.account(external), queryFn: () => getAccount(external) })
  const capabilitiesQuery = useQuery({ queryKey: queryKeys.capabilities({ status: 'ACTIVE' }), queryFn: () => listCapabilities({ status: 'ACTIVE' }) })
  const plansQuery = useQuery({ queryKey: queryKeys.plans(), queryFn: listPlans })

  const [openTraceFor, setOpenTraceFor] = useState<string | null>(null)
  const traceQuery = useQuery({
    queryKey: queryKeys.check({ account: external, capability: openTraceFor ?? undefined }),
    queryFn: () => checkDecision({ account: external, capability: openTraceFor! }),
    enabled: openTraceFor !== null,
  })

  const invalidateAccount = () => queryClient.invalidateQueries({ queryKey: queryKeys.account(external) })

  // --- Add override ---
  const [addingOverride, setAddingOverride] = useState(false)
  const [newCapability, setNewCapability] = useState('')
  const [newKind, setNewKind] = useState<OverrideKind | ''>('')
  const [newValue, setNewValue] = useState<EntitlementValue | null>(null)
  const [newReason, setNewReason] = useState('')
  const [addedDecision, setAddedDecision] = useState<Decision | null>(null)

  const addMutation = useMutation({
    mutationFn: () => addOverride(external, { capability: newCapability, kind: newKind as OverrideKind, value: newValue!, reason: newReason }),
    onSuccess: (result) => {
      setAddedDecision(result.decision)
      invalidateAccount()
      setNewReason('')
    },
  })

  function selectNewCapability(key: string) {
    setNewCapability(key)
    const cap = capabilitiesQuery.data?.capabilities.find((c) => c.key === key)
    setNewValue(cap ? zeroValueFor(cap.valueType) : null)
  }

  // --- Remove override ---
  const [confirmingRemoveId, setConfirmingRemoveId] = useState<string | null>(null)
  const [removedDecision, setRemovedDecision] = useState<Decision | null>(null)
  const removeMutation = useMutation({
    mutationFn: (id: string) => removeOverride(external, id),
    onSuccess: (result) => { setRemovedDecision(result.decision); invalidateAccount(); setConfirmingRemoveId(null) },
  })

  // --- Change plan ---
  const [changingPlan, setChangingPlan] = useState(false)
  const [newPlanKey, setNewPlanKey] = useState('')
  const [newPlanSource, setNewPlanSource] = useState<AssignmentSource>('PERSON')
  const [retainedOverrideCount, setRetainedOverrideCount] = useState<number | null>(null)
  const changePlanMutation = useMutation({
    mutationFn: () => setAccountPlan(external, { planKey: newPlanKey, source: newPlanSource, actor: 'dev-operator' }),
    onSuccess: (result) => { setRetainedOverrideCount(result.retainedOverrideCount); invalidateAccount() },
  })

  if (!accountQuery.data) return <p>Loading…</p>
  const account = accountQuery.data

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{account.name ?? account.account}</h1>
      <dl>
        <dt>Plan</dt><dd>{account.plan.name}</dd>
        <dt>Assigned</dt>
        <dd>{account.plan.assignedAt} by {account.plan.assignedBy} ({account.plan.source === 'PERSON' ? 'a person' : 'an upstream system'})</dd>
      </dl>
      <button type="button" className="sv-btn--secondary" onClick={() => setChangingPlan((v) => !v)}>Change plan</button>
      {changingPlan && (
        <form onSubmit={(e) => { e.preventDefault(); changePlanMutation.mutate() }}>
          <label className="sv-label">New plan
            <select className="sv-field" aria-label="New plan" value={newPlanKey} onChange={(e) => setNewPlanKey(e.target.value)}>
              <option value="" disabled>Select a plan</option>
              {plansQuery.data?.plans.map((p) => <option key={p.key} value={p.key}>{p.name}</option>)}
            </select>
          </label>
          <label><input type="radio" name="plan-source" checked={newPlanSource === 'PERSON'} onChange={() => setNewPlanSource('PERSON')} /> A person</label>
          <label><input type="radio" name="plan-source" checked={newPlanSource === 'SYSTEM'} onChange={() => setNewPlanSource('SYSTEM')} /> An upstream system</label>
          <button type="submit" className="sv-btn" disabled={!newPlanKey}>Confirm plan change</button>
        </form>
      )}
      {retainedOverrideCount !== null && (
        <p role="status">{`Plan changed. ${retainedOverrideCount} overrides are retained.`}</p>
      )}

      <h2>Effective entitlements</h2>
      <table>
        <thead><tr><th>Capability</th><th>Value</th><th>Source</th><th /></tr></thead>
        <tbody>
          {account.entitlements.map((row) => (
            <tr key={row.capability}>
              <td>{row.capability}</td>
              <td><ValueBadge value={row.value} tiers={[]} /></td>
              <td>{row.source}{row.sourceDetail?.reason ? ` — ${row.sourceDetail.reason}` : ''}</td>
              <td><button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(row.capability)}>Why?</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {openTraceFor && traceQuery.data && (
        <div className="app-panel">
          <h3>{openTraceFor}</h3>
          <TraceView trace={traceQuery.data.trace} />
          <button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(null)}>Close</button>
        </div>
      )}

      <h2>Overrides</h2>
      <ul>
        {account.overrides.map((o) => (
          <li key={o.id}>
            {o.kind} <ValueBadge value={o.value} tiers={[]} /> — {o.reason} — {o.createdBy}, {o.createdAt} — {EFFECT_LABELS[o.effectNow]}
            {confirmingRemoveId === o.id ? (
              <span>
                {o.kind === 'HOLD'
                  ? ' Removal is permitted and audited but not restricted.'
                  : ' The value will return to whatever the plan and remaining overrides produce.'}
                <button type="button" className="sv-btn" onClick={() => removeMutation.mutate(o.id)}>Confirm removal</button>
                <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingRemoveId(null)}>Cancel</button>
              </span>
            ) : (
              <button type="button" className="sv-btn--secondary" data-testid={`remove-${o.id}`} onClick={() => setConfirmingRemoveId(o.id)}>Remove</button>
            )}
          </li>
        ))}
      </ul>
      {removedDecision && (
        <div className="app-panel">
          <h3>Restored value</h3>
          <TraceView trace={removedDecision.trace} />
        </div>
      )}

      <button type="button" className="sv-btn--secondary" onClick={() => setAddingOverride((v) => !v)}>Add override</button>
      {addingOverride && (
        <form onSubmit={(e) => { e.preventDefault(); addMutation.mutate() }}>
          <label className="sv-label">Capability
            <select className="sv-field" aria-label="Capability" value={newCapability} onChange={(e) => selectNewCapability(e.target.value)}>
              <option value="" disabled>Select a capability</option>
              {groupByArea(capabilitiesQuery.data?.capabilities ?? []).map(([area, caps]) => (
                <optgroup key={area} label={area}>
                  {caps.map((c) => <option key={c.key} value={c.key}>{c.displayName}</option>)}
                </optgroup>
              ))}
            </select>
          </label>
          <label className="sv-label">Kind
            <select className="sv-field" aria-label="Kind" value={newKind} onChange={(e) => setNewKind(e.target.value as OverrideKind)}>
              <option value="" disabled>Select a kind</option>
              <option value="GRANT">GRANT</option>
              <option value="HOLD">HOLD</option>
            </select>
          </label>
          {newValue && (
            <ValueEditor
              valueType={capabilitiesQuery.data!.capabilities.find((c) => c.key === newCapability)!.valueType}
              tiers={capabilitiesQuery.data!.capabilities.find((c) => c.key === newCapability)!.tiers}
              value={newValue}
              onChange={setNewValue}
            />
          )}
          <label className="sv-label">Reason
            <input className="sv-field" aria-label="Reason" value={newReason} onChange={(e) => setNewReason(e.target.value)} />
          </label>
          <button type="submit" className="sv-btn" disabled={!newCapability || !newKind || newReason.trim() === ''}>Save override</button>
        </form>
      )}
      {addedDecision && (
        <div className="app-panel">
          <h3>Resulting decision</h3>
          <TraceView trace={addedDecision.trace} />
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Confirm all eight tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/accounts
```

Expected: PASS, 11 tests total (8 in `AccountDetailRoute.test.tsx`, 3 in `AccountsListRoute.test.tsx`).

- [ ] **Step 5: Commit**

```bash
git add management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.tsx \
        management/frontend/management-ui/src/routes/accounts/AccountDetailRoute.test.tsx
git commit -m "frontend: add/remove override, change plan (c9, c14, c15, c36)"
```

---

## Phase 5 — Screen 4: Checker (`/checker`)

### Task 19: Checker — decision, trace, three named errors, copy explanation (c19, c24, c38)

**Files:**
- Modify: `management/frontend/management-ui/src/test/mocks/handlers.ts` (the `GET /admin/v1/check` handler now resolves an `override` reference and returns `entitlement/retired-capability` for a retired capability — already folded into Task 3's listing above)
- Create: `management/frontend/management-ui/src/routes/checker/CheckerRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/checker/CheckerRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `checkDecision` from `../../api/checker`; `ApiError` from `../../api/http`; `TraceView`.
- Produces: the `/checker` route — "the screen the whole service exists to make possible" (`ui-screens.md`).

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/checker/CheckerRoute.test.tsx
import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CheckerRoute } from './CheckerRoute'
import { db } from '../../test/mocks/handlers'

describe('CheckerRoute', () => {
  it('checks an account and capability and renders the trace', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
    expect(screen.getByText(/Snapshot v48211/)).toBeInTheDocument()
  })

  it('resolves an override reference to its account and capability', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Override reference'), 'ovr_7788')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('renders "No such account" as an error, never a denial', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await user.type(screen.getByLabelText('Account'), 'acct_does_not_exist')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText('No such account.')).toBeInTheDocument())
    expect(screen.queryByText(/allowed: false/i)).not.toBeInTheDocument()
  })

  it('renders "That capability is retired and is no longer evaluated" for a retired capability', async () => {
    db.capabilities.find((c) => c.key === 'reports.monthly')!.status = 'RETIRED'
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText('That capability is retired and is no longer evaluated.')).toBeInTheDocument())
  })

  it('copies the rendered explanation as text', async () => {
    const user = userEvent.setup()
    Object.assign(navigator, { clipboard: { writeText: vi.fn() } })
    renderWithProviders(<CheckerRoute />)
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Copy explanation' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Copy explanation' }))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('Most restrictive HOLD'))
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/checker/CheckerRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/checker/CheckerRoute.tsx
import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { checkDecision } from '../../api/checker'
import { ApiError } from '../../api/http'
import { queryKeys } from '../../queries/keys'
import { TraceView } from '../../components/TraceView'

const ERROR_MESSAGES: Record<string, string> = {
  'entitlement/unknown-account': 'No such account.',
  'entitlement/unknown-capability': 'No such capability.',
  'entitlement/retired-capability': 'That capability is retired and is no longer evaluated.',
}

interface CheckParams { account: string; capability?: string; override?: string }

export function CheckerRoute() {
  const [account, setAccount] = useState('')
  const [capability, setCapability] = useState('')
  const [overrideRef, setOverrideRef] = useState('')
  const [submitted, setSubmitted] = useState<CheckParams | null>(null)

  const query = useQuery({
    queryKey: queryKeys.check(submitted ?? { account: '' }),
    queryFn: () => checkDecision(submitted!),
    enabled: submitted !== null,
    retry: false,
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmitted(overrideRef ? { account, override: overrideRef } : { account, capability })
  }

  function copyExplanation() {
    if (!query.data) return
    const { trace } = query.data
    const lines = [
      `Account: ${query.data.account}`,
      `Capability: ${query.data.capability}`,
      `Allowed: ${query.data.allowed}`,
      trace.baseline.note,
      trace.grantStep.note ?? '',
      trace.holdStep.note ?? '',
      `Result: allowed=${trace.result.allowed}`,
    ].filter(Boolean)
    navigator.clipboard.writeText(lines.join('\n'))
  }

  const errorType = query.error instanceof ApiError ? query.error.problem.type : null

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Checker</h1>
      <form onSubmit={handleSubmit}>
        <label className="sv-label">Account
          <input className="sv-field" aria-label="Account" value={account} onChange={(e) => setAccount(e.target.value)} />
        </label>
        <label className="sv-label">Capability
          <input className="sv-field" aria-label="Capability" value={capability} disabled={overrideRef !== ''} onChange={(e) => setCapability(e.target.value)} />
        </label>
        <label className="sv-label">Override reference
          <input className="sv-field" aria-label="Override reference" value={overrideRef} disabled={capability !== ''} onChange={(e) => setOverrideRef(e.target.value)} />
        </label>
        <button type="submit" className="sv-btn" disabled={!account || (!capability && !overrideRef)}>Check</button>
      </form>

      {errorType && <p role="alert">{ERROR_MESSAGES[errorType] ?? 'An error occurred.'}</p>}

      {query.data && (
        <div>
          <p>Allowed: {String(query.data.allowed)} · Snapshot v{query.data.snapshotVersion} · Evaluated {query.data.evaluatedAt}</p>
          <TraceView trace={query.data.trace} />
          <button type="button" className="sv-btn--secondary" onClick={copyExplanation}>Copy explanation</button>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/checker/CheckerRoute.test.tsx
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Register the route** (full `router.tsx`, extending Task 17's version)

```tsx
// src/router.tsx
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

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
const planEditorRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute /> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute })
const accountDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts/$external', component: () => <AccountDetailRoute /> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: CheckerRoute })
// Still a placeholder — kept so AppLayout's typed nav link to the not-yet-built History screen compiles.
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, planEditorRoute,
  accountsRoute, accountDetailRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add management/frontend/management-ui/src/test/mocks/handlers.ts \
        management/frontend/management-ui/src/routes/checker/CheckerRoute.tsx \
        management/frontend/management-ui/src/routes/checker/CheckerRoute.test.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: checker — decision, trace, named errors, copy explanation (c19, c24, c38)"
```

---

## Phase 6 — Screen 5: Change history (`/history`)

### Task 20: History — filters, before/after, affected-account count, no edit control (c32, c33, c34)

**Files:**
- Modify: `management/frontend/management-ui/src/test/mocks/fixtures.ts` (adds an `OVERRIDE` CREATE and REMOVE event to `makeAuditEvents` — already folded into Task 3's listing above)
- Modify: `management/frontend/management-ui/src/test/mocks/handlers.ts` (the `GET /admin/v1/audit` handler now applies `account`/`planKey`/`actor`/`entityType` filters — already folded into Task 3's listing above)
- Modify: `management/frontend/management-ui/src/api/audit.test.ts` (updated for the three-event fixture — already folded into Task 5's listing above)
- Create: `management/frontend/management-ui/src/routes/history/HistoryRoute.tsx`
- Create: `management/frontend/management-ui/src/routes/history/HistoryRoute.test.tsx`
- Modify: `management/frontend/management-ui/src/router.tsx`

**Interfaces:**
- Consumes: `listAuditEvents`, `AuditQuery` from `../../api/audit`; `ValueBadge`.
- Produces: the `/history` route — the last of the five §9 screens.

- [ ] **Step 1: Write the failing tests**

```tsx
// src/routes/history/HistoryRoute.test.tsx
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { HistoryRoute } from './HistoryRoute'

describe('HistoryRoute', () => {
  it('lists events newest first with who, what, before and after', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(4)) // header + 3 events
    const rows = screen.getAllByRole('row')
    expect(rows[1]).toHaveTextContent('a.reyes')
    expect(rows[1]).toHaveTextContent('person')
  })

  it('shows the affected-account count on a plan-entitlement row', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByText(/affected 26890 accounts/)).toBeInTheDocument())
  })

  it('shows the reason on an override row and who removed it', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByText(/Investigation closed/)).toBeInTheDocument())
    const removalRow = screen.getByText(/Investigation closed/).closest('tr')!
    expect(removalRow).toHaveTextContent('a.reyes')
    expect(removalRow).toHaveTextContent('REMOVE')
  })

  it('filters by actor', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(4))
    await user.type(screen.getByLabelText('Actor'), 'billing-bot')
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2)) // header + 1 event
    expect(screen.getByText(/Suspended pending billing investigation/)).toBeInTheDocument()
  })

  it('offers no edit, delete, or export control', () => {
    renderWithProviders(<HistoryRoute />)
    expect(screen.queryByRole('button', { name: /edit|delete|export/i })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Confirm failure**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/history/HistoryRoute.test.tsx
```

- [ ] **Step 3: Implement**

```tsx
// src/routes/history/HistoryRoute.tsx
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listAuditEvents } from '../../api/audit'
import { queryKeys } from '../../queries/keys'
import { ValueBadge } from '../../components/ValueEditor'

const ENTITY_TYPES = ['CAPABILITY', 'CAPABILITY_TIER', 'PLAN', 'PLAN_ENTITLEMENT', 'ACCOUNT', 'ACCOUNT_PLAN', 'DEFAULT_PLAN', 'OVERRIDE'] as const

export function HistoryRoute() {
  const [account, setAccount] = useState('')
  const [planKey, setPlanKey] = useState('')
  const [actor, setActor] = useState('')
  const [entityType, setEntityType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  const params = {
    account: account || undefined, planKey: planKey || undefined, actor: actor || undefined,
    entityType: entityType || undefined, from: from || undefined, to: to || undefined,
  }
  const query = useQuery({ queryKey: queryKeys.audit(params), queryFn: () => listAuditEvents(params) })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Change history</h1>
      <form onSubmit={(e) => e.preventDefault()}>
        <label className="sv-label">Account
          <input className="sv-field" aria-label="Account" value={account} onChange={(e) => setAccount(e.target.value)} />
        </label>
        <label className="sv-label">Plan
          <input className="sv-field" aria-label="Plan" value={planKey} onChange={(e) => setPlanKey(e.target.value)} />
        </label>
        <label className="sv-label">Actor
          <input className="sv-field" aria-label="Actor" value={actor} onChange={(e) => setActor(e.target.value)} />
        </label>
        <label className="sv-label">Entity type
          <select className="sv-field" aria-label="Entity type" value={entityType} onChange={(e) => setEntityType(e.target.value)}>
            <option value="">All</option>
            {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label className="sv-label">From
          <input className="sv-field" type="date" aria-label="From" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="sv-label">To
          <input className="sv-field" type="date" aria-label="To" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
      </form>

      <table>
        <thead><tr><th>When</th><th>Who</th><th>What changed</th><th>Before</th><th>After</th></tr></thead>
        <tbody>
          {query.data?.events.map((event) => (
            <tr key={event.seq}>
              <td>{event.occurredAt}</td>
              <td>{event.actor.id} ({event.actor.kind === 'PERSON' ? 'person' : 'system'})</td>
              <td>
                {event.action} {event.entityType} {event.capability ?? event.planKey ?? event.entityId}
                {event.reason && ` — ${event.reason}`}
                {event.affectedAccountCount !== null && ` — affected ${event.affectedAccountCount} accounts`}
              </td>
              <td>{event.before ? <ValueBadge value={event.before} tiers={[]} /> : '—'}</td>
              <td>{event.after ? <ValueBadge value={event.after} tiers={[]} /> : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 4: Confirm the tests pass**

```bash
cd management/frontend/management-ui && npx vitest run src/routes/history/HistoryRoute.test.tsx
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Register the final route** (full `router.tsx`, extending Task 19's version)

```tsx
// src/router.tsx
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

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesListRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansListRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
const planEditorRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute /> })
const accountsListRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute })
const accountDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts/$external', component: () => <AccountDetailRoute /> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: CheckerRoute })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: HistoryRoute })

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

- [ ] **Step 6: Run the full suite and build once more**

```bash
cd management/frontend/management-ui && npm test && npm run build
```

Expected: every test file passes; the production build succeeds with all five screens reachable from `AppLayout`'s nav.

- [ ] **Step 7: Commit**

```bash
git add management/frontend/management-ui/src/test/mocks/fixtures.ts \
        management/frontend/management-ui/src/test/mocks/handlers.ts \
        management/frontend/management-ui/src/api/audit.test.ts \
        management/frontend/management-ui/src/routes/history/HistoryRoute.tsx \
        management/frontend/management-ui/src/routes/history/HistoryRoute.test.tsx \
        management/frontend/management-ui/src/router.tsx
git commit -m "frontend: change history — filters, before/after, affected-account count (c32-c34)"
```
