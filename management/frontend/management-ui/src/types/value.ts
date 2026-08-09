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
      return 'unlimited' in value && value.unlimited ? 'Unlimited' : String(value.amount)
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
    const aUnlimited = 'unlimited' in a && a.unlimited
    const bUnlimited = 'unlimited' in b && b.unlimited
    if (aUnlimited || bUnlimited) return Boolean(aUnlimited) === Boolean(bUnlimited)
    return a.amount === b.amount
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
