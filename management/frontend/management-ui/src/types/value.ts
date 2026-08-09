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
