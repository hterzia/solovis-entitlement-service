import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getAccount, addOverride, removeOverride, setAccountPlan } from '../../api/accounts'
import { checkDecision } from '../../api/checker'
import { listPlans } from '../../api/plans'
import { listCapabilities } from '../../api/capabilities'
import { getMeta } from '../../api/meta'
import { queryKeys } from '../../queries/keys'
import { ValueBadge, ValueEditor } from '../../components/ValueEditor'
import { TraceView } from '../../components/TraceView'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import { zeroValueFor } from '../../types/value'
import type { EntitlementValue } from '../../types/value'
import type { AssignmentSource, Decision, OverrideEffect, OverrideKind } from '../../types/domain'

const EFFECT_LABELS: Record<OverrideEffect, string> = {
  WINNING: 'winning',
  OVERRIDDEN_BY_HOLD: 'overridden by a HOLD',
  SUPERSEDED_BY_GRANT: 'superseded by a larger or newer GRANT',
  SUPERSEDED_BY_STRICTER_HOLD: 'superseded by a stricter or newer HOLD',
  NO_EFFECT_PLAN_MORE_GENEROUS: 'no effect — plan is more generous',
  NO_EFFECT_NOT_MORE_RESTRICTIVE: 'no effect — not more restrictive than the result',
}

function groupByArea<T extends { area: string }>(items: T[]): [string, T[]][] {
  const byArea = new Map<string, T[]>()
  for (const item of items) {
    const bucket = byArea.get(item.area) ?? []
    bucket.push(item)
    byArea.set(item.area, bucket)
  }
  return [...byArea.entries()].sort(([a], [b]) => a.localeCompare(b))
}

export function AccountDetailRoute({ external: externalProp }: { external?: string } = {}) {
  const params = useParams({ strict: false }) as { external?: string }
  const external = externalProp ?? params.external!
  const queryClient = useQueryClient()

  const accountQuery = useQuery({ queryKey: queryKeys.account(external), queryFn: () => getAccount(external) })
  const capabilitiesQuery = useQuery({ queryKey: queryKeys.capabilities({ status: 'ACTIVE' }), queryFn: () => listCapabilities({ status: 'ACTIVE' }) })
  const plansQuery = useQuery({ queryKey: queryKeys.plans(), queryFn: listPlans })
  const meta = useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })

  const [openTraceFor, setOpenTraceFor] = useState<string | null>(null)
  const traceQuery = useQuery({
    queryKey: queryKeys.check({ account: external, capability: openTraceFor ?? undefined }),
    queryFn: () => checkDecision({ account: external, capability: openTraceFor! }),
    enabled: openTraceFor !== null,
  })

  const invalidateAccount = () => queryClient.invalidateQueries({ queryKey: queryKeys.account(external) })

  // --- Add override ---
  const [addingOverride, setAddingOverride] = useState(false)
  const [newCapability, setNewCapability] = useState('')
  const [newKind, setNewKind] = useState<OverrideKind | ''>('')
  const [newValue, setNewValue] = useState<EntitlementValue | null>(null)
  const [newReason, setNewReason] = useState('')
  const [addedDecision, setAddedDecision] = useState<Decision | null>(null)

  const addMutation = useMutation({
    mutationFn: () => addOverride(external, { capability: newCapability, kind: newKind as OverrideKind, value: newValue!, reason: newReason }),
    onSuccess: (result) => {
      setAddedDecision(result.decision)
      invalidateAccount()
      setNewReason('')
    },
  })

  function selectNewCapability(key: string) {
    setNewCapability(key)
    const cap = capabilitiesQuery.data?.capabilities.find((c) => c.key === key)
    setNewValue(cap ? zeroValueFor(cap.valueType) : null)
  }

  // --- Remove override ---
  const [confirmingRemoveId, setConfirmingRemoveId] = useState<string | null>(null)
  const [removedDecision, setRemovedDecision] = useState<Decision | null>(null)
  const removeMutation = useMutation({
    mutationFn: (id: string) => removeOverride(external, id),
    onSuccess: (result) => { setRemovedDecision(result.decision); invalidateAccount(); setConfirmingRemoveId(null) },
  })

  // --- Change plan ---
  const [changingPlan, setChangingPlan] = useState(false)
  const [newPlanKey, setNewPlanKey] = useState('')
  const [newPlanSource, setNewPlanSource] = useState<AssignmentSource>('PERSON')
  const [retainedOverrideCount, setRetainedOverrideCount] = useState<number | null>(null)
  const changePlanMutation = useMutation({
    mutationFn: () => setAccountPlan(external, { planKey: newPlanKey, source: newPlanSource, actor: 'dev-operator' }),
    onSuccess: (result) => { setRetainedOverrideCount(result.retainedOverrideCount); invalidateAccount() },
  })

  // Each action's result panel describes only its own action, so starting a different one
  // must clear the previous panel rather than leave two contradictory results on screen.
  function clearActionResults() {
    setAddedDecision(null)
    setRemovedDecision(null)
    setRetainedOverrideCount(null)
  }

  if (!accountQuery.data) return <p>Loading…</p>
  const account = accountQuery.data

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{account.name ?? account.account}</h1>
      <dl>
        <dt>Plan</dt><dd>{account.plan.name}</dd>
        <dt>Assigned</dt>
        <dd>{account.plan.assignedAt} by {account.plan.assignedBy} ({account.plan.source === 'PERSON' ? 'a person' : 'an upstream system'})</dd>
      </dl>
      <button
        type="button"
        className="sv-btn--secondary"
        onClick={() => setChangingPlan((v) => { if (!v) clearActionResults(); return !v })}
      >
        Change plan
      </button>
      {changingPlan && (
        <form onSubmit={(e) => { e.preventDefault(); changePlanMutation.mutate() }}>
          <p>{`${account.overrides.length} overrides on this account will be kept when the plan changes.`}</p>
          <label className="sv-label">New plan
            <select className="sv-field" aria-label="New plan" value={newPlanKey} onChange={(e) => setNewPlanKey(e.target.value)}>
              <option value="" disabled>Select a plan</option>
              {plansQuery.data?.plans.map((p) => <option key={p.key} value={p.key}>{p.name}</option>)}
            </select>
          </label>
          <label><input type="radio" name="plan-source" checked={newPlanSource === 'PERSON'} onChange={() => setNewPlanSource('PERSON')} /> A person</label>
          <label><input type="radio" name="plan-source" checked={newPlanSource === 'SYSTEM'} onChange={() => setNewPlanSource('SYSTEM')} /> An upstream system</label>
          <button type="submit" className="sv-btn" disabled={!newPlanKey}>Confirm plan change</button>
        </form>
      )}
      {retainedOverrideCount !== null && (
        <>
          <p role="status">{`Plan changed. ${retainedOverrideCount} overrides are retained.`}</p>
          {changePlanMutation.isSuccess && meta.data && <SaveConfirmation seconds={meta.data.changeVisibleEverywhereWithinSeconds} />}
        </>
      )}

      <h2>Effective entitlements</h2>
      <table>
        <thead><tr><th>Capability</th><th>Value</th><th>Source</th><th /></tr></thead>
        <tbody>
          {account.entitlements.map((row) => (
            <tr key={row.capability}>
              <td>{row.capability}</td>
              <td><ValueBadge value={row.value} tiers={[]} /></td>
              <td>{row.source}{row.sourceDetail?.reason ? ` — ${row.sourceDetail.reason}` : ''}</td>
              <td><button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(row.capability)}>Why?</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {openTraceFor && traceQuery.data && (
        <div className="app-panel">
          <h3>{openTraceFor}</h3>
          <TraceView
            trace={traceQuery.data.trace}
            tiers={capabilitiesQuery.data?.capabilities.find((c) => c.key === openTraceFor)?.tiers ?? []}
          />
          <button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(null)}>Close</button>
        </div>
      )}

      <h2>Overrides</h2>
      <ul>
        {account.overrides.map((o) => (
          <li key={o.id}>
            {o.kind} <ValueBadge value={o.value} tiers={[]} /> — {o.reason} — {o.createdBy}, {o.createdAt} — {EFFECT_LABELS[o.effectNow]}
            {confirmingRemoveId === o.id ? (
              <span>
                {o.kind === 'HOLD'
                  ? ' Removal is permitted and audited but not restricted.'
                  : ' The value will return to whatever the plan and remaining overrides produce.'}
                <button type="button" className="sv-btn" onClick={() => removeMutation.mutate(o.id)}>Confirm removal</button>
                <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingRemoveId(null)}>Cancel</button>
              </span>
            ) : (
              <button type="button" className="sv-btn--secondary" data-testid={`remove-${o.id}`} onClick={() => { clearActionResults(); setConfirmingRemoveId(o.id) }}>Remove</button>
            )}
          </li>
        ))}
      </ul>
      {removedDecision && removeMutation.data && (
        <div className="app-panel">
          <h3>Restored value</h3>
          <TraceView trace={removedDecision.trace} />
          <SaveConfirmation seconds={removeMutation.data.changeVisibleEverywhereWithinSeconds} />
        </div>
      )}

      <button
        type="button"
        className="sv-btn--secondary"
        onClick={() => setAddingOverride((v) => { if (!v) clearActionResults(); return !v })}
      >
        Add override
      </button>
      {addingOverride && (
        <form onSubmit={(e) => { e.preventDefault(); addMutation.mutate() }}>
          <label className="sv-label">Capability
            <select className="sv-field" aria-label="Capability" value={newCapability} onChange={(e) => selectNewCapability(e.target.value)}>
              <option value="" disabled>Select a capability</option>
              {groupByArea(capabilitiesQuery.data?.capabilities ?? []).map(([area, caps]) => (
                <optgroup key={area} label={area}>
                  {caps.map((c) => <option key={c.key} value={c.key}>{c.displayName}</option>)}
                </optgroup>
              ))}
            </select>
          </label>
          <label className="sv-label">Kind
            <select className="sv-field" aria-label="Kind" value={newKind} onChange={(e) => setNewKind(e.target.value as OverrideKind)}>
              <option value="" disabled>Select a kind</option>
              <option value="GRANT">GRANT</option>
              <option value="HOLD">HOLD</option>
            </select>
          </label>
          {newValue && (
            <ValueEditor
              valueType={capabilitiesQuery.data!.capabilities.find((c) => c.key === newCapability)!.valueType}
              tiers={capabilitiesQuery.data!.capabilities.find((c) => c.key === newCapability)!.tiers}
              value={newValue}
              onChange={setNewValue}
            />
          )}
          <label className="sv-label">Reason
            <input className="sv-field" aria-label="Reason" value={newReason} onChange={(e) => setNewReason(e.target.value)} />
          </label>
          <button type="submit" className="sv-btn" disabled={!newCapability || !newKind || newReason.trim() === ''}>Save override</button>
        </form>
      )}
      {addedDecision && (
        <div className="app-panel">
          <h3>Resulting decision</h3>
          <TraceView trace={addedDecision.trace} />
          {addMutation.data && <SaveConfirmation seconds={addMutation.data.changeVisibleEverywhereWithinSeconds} />}
        </div>
      )}
    </div>
  )
}
