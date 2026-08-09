import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { listCapabilities } from '../../api/capabilities'
import { getPlan } from '../../api/plans'
import { queryKeys } from '../../queries/keys'
import { CapabilityTree } from '../../components/CapabilityTree'
import { ValueEditor, ValueBadge } from '../../components/ValueEditor'
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

  const [pendingSet, setPendingSet] = useState<Record<string, EntitlementValue>>({})
  const [pendingUnset, setPendingUnset] = useState<Set<string>>(new Set())
  const [editing, setEditing] = useState<string | null>(null)

  if (!planQuery.data || !capabilitiesQuery.data) return <p>Loading…</p>

  const originalByCapability = new Map(planQuery.data.entitlements.map((e) => [e.capability, e.value]))

  const rows: PlanEditorRow[] = capabilitiesQuery.data.capabilities.map((cap) => {
    const original = originalByCapability.get(cap.key) ?? null
    const planValue = pendingUnset.has(cap.key) ? null : (pendingSet[cap.key] ?? original)
    return { key: cap.key, area: cap.area, displayName: cap.displayName, capability: cap, planValue }
  })

  function setCapabilityValue(cap: Capability, value: EntitlementValue) {
    setPendingSet((prev) => ({ ...prev, [cap.key]: value }))
    setPendingUnset((prev) => { const next = new Set(prev); next.delete(cap.key); return next })
  }

  function clearCapabilityValue(cap: Capability) {
    setPendingUnset((prev) => new Set(prev).add(cap.key))
    setPendingSet((prev) => { const next = { ...prev }; delete next[cap.key]; return next })
    setEditing(null)
  }

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
    </div>
  )
}
