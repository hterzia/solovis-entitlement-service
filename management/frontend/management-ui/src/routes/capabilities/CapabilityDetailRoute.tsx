import { useEffect, useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getCapability, updateCapability, addCapabilityTier, retireCapability } from '../../api/capabilities'
import { getMeta } from '../../api/meta'
import { queryKeys } from '../../queries/keys'
import { ValueEditor } from '../../components/ValueEditor'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import { ErrorNotice } from '../../components/ErrorNotice'
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
  const [offValue, setOffValue] = useState<EntitlementValue | null>(null)
  const [newTierKey, setNewTierKey] = useState('')
  const [newTierName, setNewTierName] = useState('')
  const [confirmingRetire, setConfirmingRetire] = useState(false)

  useEffect(() => {
    if (capability.data) {
      setDisplayName(capability.data.displayName)
      setDescription(capability.data.description ?? '')
      setDefaultValue(capability.data.default)
      setOffValue(capability.data.offValue)
    }
  }, [capability.data])

  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.capability(key) })

  const saveMutation = useMutation({
    mutationFn: () => updateCapability(key, { displayName, description: description || null, default: defaultValue!, offValue }),
    onSuccess: invalidate,
  })
  const tierMutation = useMutation({
    mutationFn: () => addCapabilityTier(key, { tier: newTierKey, displayName: newTierName }),
    onSuccess: () => { invalidate(); setNewTierKey(''); setNewTierName('') },
  })
  const retireMutation = useMutation({
    mutationFn: () => retireCapability(key),
    onSuccess: () => { invalidate(); setConfirmingRetire(false) },
  })

  // A capability that cannot be read is a different state from one that has not arrived yet.
  // Collapsing the two left a 404 showing "Loading…" for ever, with nothing to diagnose from.
  if (capability.isError) {
    return (
      <div className="app-panel">
        <ErrorNotice error={capability.error} action="Could not load this capability" />
      </div>
    )
  }
  if (!capability.data || !defaultValue) return <p>Loading…</p>
  const cap = capability.data

  const offAtZero = offValue?.type === 'QUANTITY' && 'amount' in offValue && offValue.amount === 0
  const offTier = offValue?.type === 'TIER' ? offValue.tier : ''

  // Editing clears the previous save's outcome, so a stale "Saved." cannot sit above a form the
  // operator has since changed. A save that is still in flight is not a previous outcome: resetting
  // it detaches the observer, and the result — failure *or* success — is never rendered while the
  // write lands anyway. So only a settled mutation is cleared.
  const clearSettledSave = () => { if (!saveMutation.isPending) saveMutation.reset() }
  const editOffValue = (next: EntitlementValue | null) => { setOffValue(next); clearSettledSave() }

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{cap.displayName}</h1>

      <p data-testid="value-type-readonly" title="A capability has one value type across every plan.">{cap.valueType}</p>

      <label className="sv-label">Display name
        <input className="sv-field" value={displayName} onChange={(e) => { setDisplayName(e.target.value); clearSettledSave() }} aria-label="Display name" />
      </label>
      <label className="sv-label">Description
        <textarea className="sv-field" value={description} onChange={(e) => { setDescription(e.target.value); clearSettledSave() }} aria-label="Description" />
      </label>
      <fieldset>
        <legend>Default value</legend>
        <ValueEditor valueType={cap.valueType} tiers={cap.tiers} value={defaultValue} onChange={(v) => { setDefaultValue(v); clearSettledSave() }} />
      </fieldset>

      {/* The §5 off-value rules, made visible the same way CapabilityCreateForm makes them visible
          at declaration time. SWITCH declares no control at all — its off is false, inherently and
          always. §12 records these edits as ungated: no affected-account preview, not no field. */}
      {cap.valueType === 'QUANTITY' && (
        <label className="sv-label">
          <input
            type="checkbox" aria-label="Off at 0" checked={offAtZero}
            onChange={(e) => editOffValue(e.target.checked ? { type: 'QUANTITY', amount: 0 } : null)}
          />
          {' '}Off at 0
        </label>
      )}
      {cap.valueType === 'TIER' && (
        <label className="sv-label">Off-value tier (optional)
          <select
            className="sv-field" aria-label="Off-value tier" value={offTier}
            onChange={(e) => editOffValue(e.target.value ? { type: 'TIER', tier: e.target.value } : null)}
          >
            <option value="">None</option>
            {cap.tiers.map((t) => <option key={t.tier} value={t.tier}>{t.displayName}</option>)}
          </select>
        </label>
      )}

      <button type="button" className="sv-btn" onClick={() => saveMutation.mutate()}>Save changes</button>
      {saveMutation.isSuccess && <SaveConfirmation seconds={meta.data?.changeVisibleEverywhereWithinSeconds} />}
      <ErrorNotice error={saveMutation.error} action="Could not save these changes" />

      {cap.valueType === 'TIER' && (
        <fieldset>
          <legend>Tiers</legend>
          <ul>{cap.tiers.map((t) => <li key={t.tier}>{t.displayName}</li>)}</ul>
          <input className="sv-field" aria-label="New tier key" value={newTierKey} onChange={(e) => setNewTierKey(e.target.value)} />
          <input className="sv-field" aria-label="New tier display name" value={newTierName} onChange={(e) => setNewTierName(e.target.value)} />
          <button type="button" className="sv-btn--secondary" onClick={() => tierMutation.mutate()} disabled={!newTierKey || !newTierName}>
            Append tier
          </button>
          <ErrorNotice error={tierMutation.error} action="Could not append this tier" />
        </fieldset>
      )}

      {cap.status === 'ACTIVE' && !confirmingRetire && (
        <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingRetire(true)}>Retire capability</button>
      )}
      {confirmingRetire && (
        <div className="app-panel">
          <p>Used by {cap.usage.plans.length} plan{cap.usage.plans.length === 1 ? '' : 's'}, {cap.usage.liveOverrides} live overrides.</p>
          <p>Retirement is permanent. This capability stays visible in history.</p>
          <button type="button" className="sv-btn" onClick={() => retireMutation.mutate()}>Confirm retirement</button>
          <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingRetire(false)}>Cancel</button>
        </div>
      )}
      {retireMutation.isSuccess && <SaveConfirmation seconds={meta.data?.changeVisibleEverywhereWithinSeconds} />}
      <ErrorNotice error={retireMutation.error} action="Could not retire this capability" />
    </div>
  )
}
