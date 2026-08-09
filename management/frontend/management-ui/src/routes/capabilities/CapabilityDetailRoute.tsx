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
