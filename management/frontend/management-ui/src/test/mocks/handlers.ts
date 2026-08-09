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
