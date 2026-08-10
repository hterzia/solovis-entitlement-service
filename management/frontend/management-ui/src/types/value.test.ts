import { describe, expect, it } from 'vitest'
import { formatAuditValue, formatValue, valuesEqual, zeroValueFor } from './value'
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

describe('formatAuditValue', () => {
  it('renders null or undefined as an em dash', () => {
    expect(formatAuditValue(null)).toBe('—')
    expect(formatAuditValue(undefined)).toBe('—')
  })

  it('renders a bare EntitlementValue the same way formatValue does', () => {
    expect(formatAuditValue({ type: 'QUANTITY', amount: 75 })).toBe('75')
    expect(formatAuditValue({ type: 'SWITCH', enabled: true })).toBe('On')
  })

  it('renders a plain object as key: value pairs, recursing into nested values', () => {
    expect(formatAuditValue({ planKey: 'pro' })).toBe('planKey: pro')
    expect(formatAuditValue({ 'reports.monthly': { type: 'QUANTITY', amount: 75 } })).toBe('reports.monthly: 75')
  })

  it('renders an empty object or array as an em dash', () => {
    expect(formatAuditValue({})).toBe('—')
    expect(formatAuditValue([])).toBe('—')
  })

  it('renders a non-empty array by formatting each element', () => {
    expect(formatAuditValue([{ type: 'QUANTITY', amount: 1 }, { type: 'QUANTITY', amount: 2 }])).toBe('1; 2')
  })

  it('renders a primitive as its string form', () => {
    expect(formatAuditValue('ACTIVE')).toBe('ACTIVE')
    expect(formatAuditValue(42)).toBe('42')
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
