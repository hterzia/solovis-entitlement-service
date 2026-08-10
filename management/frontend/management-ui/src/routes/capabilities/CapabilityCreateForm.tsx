import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createCapability } from '../../api/capabilities'
import type { CreateCapabilityInput } from '../../api/capabilities'
import { getMeta } from '../../api/meta'
import { queryKeys } from '../../queries/keys'
import { ValueEditor } from '../../components/ValueEditor'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import { ErrorNotice } from '../../components/ErrorNotice'
import { zeroValueFor } from '../../types/value'
import type { EntitlementValue, ValueType } from '../../types/value'

interface TierDraft { tier: string; displayName: string }

export function CapabilityCreateForm({ onCreated, onPendingChange }: { onCreated: () => void; onPendingChange?: (pending: boolean) => void }) {
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

  // Dismissing the form while its write is in flight unmounts the notice that would have reported
  // the outcome — the request is already sent, so the result would be lost rather than cancelled.
  // The parent disables its Cancel control for the round trip instead.
  useEffect(() => { onPendingChange?.(mutation.isPending) }, [mutation.isPending, onPendingChange])

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
      {mutation.isSuccess && <SaveConfirmation seconds={meta.data?.changeVisibleEverywhereWithinSeconds} />}
      <ErrorNotice error={mutation.error} action="Could not declare this capability" />
    </form>
  )
}
