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
