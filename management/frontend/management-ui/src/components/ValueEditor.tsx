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
