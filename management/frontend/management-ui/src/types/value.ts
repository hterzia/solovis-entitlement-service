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

function isEntitlementValue(value: unknown): value is EntitlementValue {
  if (typeof value !== 'object' || value === null || !('type' in value)) return false
  const type = (value as { type: unknown }).type
  return type === 'SWITCH' || type === 'QUANTITY' || type === 'TIER'
}

/**
 * The audit trail logs a different shape per entity type — a bare value for an override or
 * plan-entitlement edit, a capability descriptor for a capability create/update, a `{planKey}`
 * map for a plan reassignment, a map of capability→value for a plan-entitlement apply, and so on
 * (see the write paths in `AccountAdminService`/`CapabilityAdminService`/`PlanAdminService`/
 * `OverrideAdminService`). There is no single wire type for "the value of a change," so this
 * renders whatever it's given rather than assuming it's always a bare `EntitlementValue`.
 */
export function formatAuditValue(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (isEntitlementValue(value)) return formatValue(value)
  if (Array.isArray(value)) return value.length === 0 ? '—' : value.map((v) => formatAuditValue(v)).join('; ')
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
    return entries.length === 0 ? '—' : entries.map(([k, v]) => `${k}: ${formatAuditValue(v)}`).join(', ')
  }
  return String(value)
}
