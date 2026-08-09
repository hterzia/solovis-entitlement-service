import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listCapabilities } from '../../api/capabilities'
import { getPlan, previewPlanEntitlements, applyPlanEntitlements } from '../../api/plans'
import type { PlanPreviewResult } from '../../api/plans'
import { queryKeys } from '../../queries/keys'
import { CapabilityTree } from '../../components/CapabilityTree'
import { ValueEditor, ValueBadge } from '../../components/ValueEditor'
import { TraceView } from '../../components/TraceView'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import { formatValue } from '../../types/value'
import type { EntitlementValue } from '../../types/value'
import type { Capability } from '../../types/domain'

interface PlanEditorRow {
  key: string
  area: string
  displayName: string
  capability: Capability
  planValue: EntitlementValue | null
}

export function PlanEditorRoute({ planKey }: { planKey?: string } = {}) {
  const params = useParams({ strict: false }) as { key?: string }
  const key = planKey ?? params.key!

  const planQuery = useQuery({ queryKey: queryKeys.plan(key), queryFn: () => getPlan(key) })
  const capabilitiesQuery = useQuery({ queryKey: queryKeys.capabilities({ status: 'ACTIVE' }), queryFn: () => listCapabilities({ status: 'ACTIVE' }) })
  const queryClient = useQueryClient()

  const [pendingSet, setPendingSet] = useState<Record<string, EntitlementValue>>({})
  const [pendingUnset, setPendingUnset] = useState<Set<string>>(new Set())
  const [editing, setEditing] = useState<string | null>(null)
  const [previewAccountInput, setPreviewAccountInput] = useState('')
  const [preview, setPreview] = useState<PlanPreviewResult | null>(null)
  const [applyResult, setApplyResult] = useState<{ changeVisibleEverywhereWithinSeconds: number } | null>(null)

  const reviewMutation = useMutation({
    mutationFn: () => previewPlanEntitlements(key, {
      set: pendingSet, unset: [...pendingUnset], previewAccount: previewAccountInput || undefined,
    }),
    onSuccess: setPreview,
  })
  const saveMutation = useMutation({
    mutationFn: () => applyPlanEntitlements(key, { set: pendingSet, unset: [...pendingUnset], previewToken: preview!.previewToken }),
    onSuccess: (result) => {
      setApplyResult(result)
      queryClient.invalidateQueries({ queryKey: queryKeys.plan(key) })
      queryClient.invalidateQueries({ queryKey: queryKeys.plans() })
      setPendingSet({})
      setPendingUnset(new Set())
      setPreview(null)
    },
  })

  if (!planQuery.data || !capabilitiesQuery.data) return <p>Loading…</p>

  const originalByCapability = new Map(planQuery.data.entitlements.map((e) => [e.capability, e.value]))
  const hasPendingChanges = Object.keys(pendingSet).length > 0 || pendingUnset.size > 0

  const rows: PlanEditorRow[] = capabilitiesQuery.data.capabilities.map((cap) => {
    const original = originalByCapability.get(cap.key) ?? null
    const planValue = pendingUnset.has(cap.key) ? null : (pendingSet[cap.key] ?? original)
    return { key: cap.key, area: cap.area, displayName: cap.displayName, capability: cap, planValue }
  })

  function setCapabilityValue(cap: Capability, value: EntitlementValue) {
    setPendingSet((prev) => ({ ...prev, [cap.key]: value }))
    setPendingUnset((prev) => { const next = new Set(prev); next.delete(cap.key); return next })
    setPreview(null)
    setApplyResult(null)
  }

  function clearCapabilityValue(cap: Capability) {
    setPendingUnset((prev) => new Set(prev).add(cap.key))
    setPendingSet((prev) => { const next = { ...prev }; delete next[cap.key]; return next })
    setEditing(null)
    setPreview(null)
    setApplyResult(null)
  }

  const canSave = preview !== null && Boolean(preview.previewAccount)

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{planQuery.data.name}</h1>
      <CapabilityTree
        items={rows}
        renderRow={(row) => (
          <div className="plan-editor-row">
            <span>{row.displayName}</span>{' '}
            {editing === row.key ? (
              <>
                <ValueEditor
                  valueType={row.capability.valueType}
                  tiers={row.capability.tiers}
                  value={row.planValue ?? row.capability.default}
                  onChange={(v) => setCapabilityValue(row.capability, v)}
                />
                <button type="button" className="sv-btn--secondary" onClick={() => setEditing(null)}>Done</button>
              </>
            ) : (
              <>
                {row.planValue ? (
                  <ValueBadge value={row.planValue} tiers={row.capability.tiers} />
                ) : (
                  <span className="sv-tag" style={{ opacity: 0.6 }}>
                    not set — falls back to default ({formatValue(row.capability.default, row.capability.tiers)})
                  </span>
                )}
                <button type="button" className="sv-btn--secondary" onClick={() => setEditing(row.key)}>Edit</button>
                {row.planValue && (
                  <button type="button" className="sv-btn--secondary" onClick={() => clearCapabilityValue(row.capability)}>Clear</button>
                )}
              </>
            )}
          </div>
        )}
      />

      {(hasPendingChanges || applyResult) && (
        <div className="app-panel">
          <h2>Review and save</h2>
          <label className="sv-label">Preview account
            <input className="sv-field" value={previewAccountInput} onChange={(e) => setPreviewAccountInput(e.target.value)} aria-label="Preview account" />
          </label>
          <button type="button" className="sv-btn--secondary" onClick={() => reviewMutation.mutate()}>Review changes</button>

          {preview && (
            <div>
              <p role="alert">{`This change affects ${preview.affectedAccountCount} accounts.`}</p>
              <ul>
                {preview.diff.map((d) => (
                  <li key={d.capability}>
                    {d.capability}: {d.before ? formatValue(d.before) : '—'} → {d.after ? formatValue(d.after) : '—'}
                    {d.note && ` (${d.note})`}
                  </li>
                ))}
              </ul>
              {preview.previewAccount && (
                <div>
                  <h3>Effect on {preview.previewAccount.account}</h3>
                  {preview.previewAccount.effects.map((effect) => (
                    <div key={effect.capability}>
                      <h4>{effect.capability}</h4>
                      {!effect.changed && <p>{effect.note ?? 'No change for this account.'}</p>}
                      <div>
                        <strong>Before</strong>
                        <TraceView trace={effect.before.trace} />
                      </div>
                      <div>
                        <strong>After</strong>
                        <TraceView trace={effect.after.trace} />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          <button type="button" className="sv-btn" disabled={!canSave} onClick={() => saveMutation.mutate()}>Save</button>
          {applyResult && <SaveConfirmation seconds={applyResult.changeVisibleEverywhereWithinSeconds} />}
        </div>
      )}
    </div>
  )
}
