import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getAccount, addOverride, removeOverride, setAccountPlan, previewOverrideRemoval } from '../../api/accounts'
import { checkDecision } from '../../api/checker'
import { listPlans } from '../../api/plans'
import { listCapabilities } from '../../api/capabilities'
import { getMeta } from '../../api/meta'
import { queryKeys } from '../../queries/keys'
import { ValueBadge, ValueEditor } from '../../components/ValueEditor'
import { TraceView } from '../../components/TraceView'
import { SaveConfirmation } from '../../components/SaveConfirmation'
import { ErrorNotice } from '../../components/ErrorNotice'
import { zeroValueFor } from '../../types/value'
import type { EntitlementValue } from '../../types/value'
import type { AssignmentSource, CapabilityStatus, Decision, EntitlementSource, Override, OverrideEffect, OverrideKind, OverrideStanding } from '../../types/domain'

const EFFECT_LABELS: Record<OverrideEffect, string> = {
  WINNING: 'winning',
  OVERRIDDEN_BY_HOLD: 'overridden by a HOLD',
  SUPERSEDED_BY_GRANT: 'superseded by a larger or newer GRANT',
  SUPERSEDED_BY_STRICTER_HOLD: 'superseded by a stricter or newer HOLD',
  NO_EFFECT_PLAN_MORE_GENEROUS: 'no effect — plan is more generous',
  NO_EFFECT_NOT_MORE_RESTRICTIVE: 'no effect — not more restrictive than the result',
}

// contracts/ui-screens.md Screen 3 words the four sources `default` · `plan` · `GRANT` · `HOLD`
// (c39). The wire enum is not those words, so it is mapped here rather than printed raw.
const SOURCE_LABELS: Record<EntitlementSource, string> = {
  CAPABILITY_DEFAULT: 'default',
  PLAN: 'plan',
  GRANT: 'GRANT',
  HOLD: 'HOLD',
}

/**
 * The current effect of an override, in §9's words. A retired capability is no longer evaluated
 * (c19), so the service sends no `effectNow` for one — that absence is a fact worth printing, not
 * an empty string to leave dangling after an em dash.
 */
function effectLabel(override: Override, capabilityStatus?: CapabilityStatus): string {
  if (override.effectNow) return EFFECT_LABELS[override.effectNow]
  return capabilityStatus === 'RETIRED' ? 'not evaluated — this capability is retired' : 'effect not stated'
}

function overrideCount(count: number): string {
  return `${count} override${count === 1 ? '' : 's'}`
}

/** The service clock every date on this screen is entered and shown against (c5). */
const SERVICE_CLOCK = 'US Eastern'

/** c18 — in force first and prominent; the rest present but visibly not counting. */
const STANDING_ORDER: OverrideStanding[] = ['IN_FORCE', 'PENDING', 'ENDED', 'REMOVED']

const STANDING_HEADINGS: Record<OverrideStanding, string> = {
  IN_FORCE: 'In force',
  PENDING: 'Not yet begun',
  ENDED: 'Ended',
  REMOVED: 'Removed',
}

/**
 * c6 — what the dates mean, in words, before the operator saves. Written here rather than taken
 * from the service because nothing has been sent yet; it restates the operator's own input, and
 * makes no claim about what the answer will be.
 */
function windowSentence(startsOn: string, expiresOn: string): string {
  if (!startsOn && !expiresOn) return `In force from now until it is removed (${SERVICE_CLOCK}).`
  if (startsOn && !expiresOn) return `In force from ${startsOn} until it is removed (${SERVICE_CLOCK}).`
  if (!startsOn && expiresOn) return `In force from now to ${expiresOn} inclusive (${SERVICE_CLOCK}).`
  return `In force from ${startsOn} to ${expiresOn} inclusive (${SERVICE_CLOCK}).`
}

/** The window an override was granted under, for the list. Reads dates; derives no standing. */
function windowSummary(o: Override): string | null {
  if (!o.startsOn && !o.expiresOn) return null
  if (o.startsOn && o.expiresOn) return `${o.startsOn} to ${o.expiresOn} inclusive`
  return o.startsOn ? `from ${o.startsOn}` : `to ${o.expiresOn} inclusive`
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
  // Every capability, retired included. An override survives its capability's retirement (c8), and
  // its row still has to name the declared tier — loading only ACTIVE ones made that lookup miss and
  // printed the raw key. The add-override picker narrows to ACTIVE below, where that is the rule.
  const capabilitiesQuery = useQuery({ queryKey: queryKeys.capabilities({ status: 'ALL' }), queryFn: () => listCapabilities({ status: 'ALL' }) })
  const capabilityFor = (key: string) => capabilitiesQuery.data?.capabilities.find((c) => c.key === key)
  const activeCapabilities = capabilitiesQuery.data?.capabilities.filter((c) => c.status === 'ACTIVE') ?? []
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
  const [newStartsOn, setNewStartsOn] = useState('')
  const [newExpiresOn, setNewExpiresOn] = useState('')
  // The capability travels with the decision because the form is emptied on success: the result
  // panel still has to know which capability's declared tiers to render its trace with.
  const [addedDecision, setAddedDecision] = useState<{ capability: string; decision: Decision } | null>(null)

  const addMutation = useMutation({
    mutationFn: () => addOverride(external, {
      capability: newCapability, kind: newKind as OverrideKind, value: newValue!, reason: newReason,
      startsOn: newStartsOn || undefined, expiresOn: newExpiresOn || undefined,
    }),
    onSuccess: (result) => {
      setAddedDecision({ capability: newCapability, decision: result.decision })
      invalidateAccount()
      setNewCapability('')
      setNewKind('')
      setNewValue(null)
      setNewReason('')
      setNewStartsOn('')
      setNewExpiresOn('')
    },
  })

  function selectNewCapability(key: string) {
    setNewCapability(key)
    const cap = capabilityFor(key)
    setNewValue(cap ? zeroValueFor(cap.valueType) : null)
  }

  // --- Remove override ---
  const [confirmingRemoveId, setConfirmingRemoveId] = useState<string | null>(null)
  // What the value returns to, asked of the service the moment the confirmation opens (c14, c15).
  // The SPA does not work this out: answering it means re-running §4's combining rule over the
  // remaining overrides, and that rule has exactly one implementation (`DECISIONS.md` §2).
  const removalPreview = useQuery({
    queryKey: queryKeys.overrideRemovalPreview(external, confirmingRemoveId ?? ''),
    queryFn: () => previewOverrideRemoval(external, confirmingRemoveId!),
    enabled: confirmingRemoveId !== null,
    retry: false,
  })
  const [removedDecision, setRemovedDecision] = useState<{ capability: string; decision: Decision } | null>(null)
  const removeMutation = useMutation({
    mutationFn: (id: string) => removeOverride(external, id),
    onSuccess: (result, id) => {
      // Read the capability off the still-current account data: the invalidation below is what
      // eventually drops the removed override, so it is still present at this point.
      const capability = accountQuery.data?.overrides.find((o) => o.id === id)?.capability ?? ''
      setRemovedDecision({ capability, decision: result.decision })
      invalidateAccount()
      setConfirmingRemoveId(null)
    },
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
  // must clear the previous panel rather than leave two contradictory results on screen. A
  // failure notice is such a result: a stale one would attach the wrong action's error to
  // whatever the operator is doing now.
  function clearActionResults() {
    setAddedDecision(null)
    setRemovedDecision(null)
    setRetainedOverrideCount(null)
    // Only settled results. A request still in flight is not a previous action's leftover: resetting
    // it detaches the observer, so the outcome never renders even though the write lands. Collapsing
    // a panel is not cancelling the write.
    if (!addMutation.isPending) addMutation.reset()
    if (!removeMutation.isPending) removeMutation.reset()
    if (!changePlanMutation.isPending) changePlanMutation.reset()
  }

  // A failed load is not a slow load. Guarding only on the absence of data left an account that
  // 404s showing "Loading…" for ever, which reads as "still working" rather than "there is no
  // such account".
  if (accountQuery.isError) {
    return (
      <div className="app-panel">
        <ErrorNotice error={accountQuery.error} action="Could not load this account" />
      </div>
    )
  }
  if (!accountQuery.data) return <p>Loading…</p>
  const account = accountQuery.data

  return (
    <div className="app-panel">
      <h1 className="app-page-title">{account.name ?? account.account}</h1>
      <dl>
        {/* The identifier is what an operator was given by the consuming product, so it has to be
            on the page even when a display name exists to stand in for it in the title. */}
        <dt>Account</dt><dd>{account.account}</dd>
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
          <p>{`${overrideCount(account.overrides.length)} on this account will be kept when the plan changes.`}</p>
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
      {/* Outside the collapsible form on purpose: a failure that arrives after the operator has
          collapsed the panel must still be seen. The request was sent; closing the form did not
          recall it. */}
      <ErrorNotice error={changePlanMutation.error} action="Could not change the plan" />
      {retainedOverrideCount !== null && (
        <>
          <p role="status">{`Plan changed. ${overrideCount(retainedOverrideCount)} ${retainedOverrideCount === 1 ? 'is' : 'are'} retained.`}</p>
          {changePlanMutation.isSuccess && <SaveConfirmation seconds={meta.data?.changeVisibleEverywhereWithinSeconds} />}
        </>
      )}

      <h2>Effective entitlements</h2>
      {/* Area-grouped, per Screen 3: a set is one switch per member, so a twelve-region residency
          set is twelve rows, and a flat list buries the one row being looked for. */}
      <table>
        <thead><tr><th>Capability</th><th>Value</th><th>Source</th><th /></tr></thead>
        {groupByArea(account.entitlements).map(([area, rows]) => (
          <tbody key={area} data-testid={`entitlement-area-${area}`}>
            <tr><th colSpan={4} scope="colgroup">{area}</th></tr>
            {rows.map((row) => (
              <tr key={row.capability} data-testid={`entitlement-${row.capability}`}>
                <td>{row.capability}</td>
                <td><ValueBadge value={row.value} tiers={capabilityFor(row.capability)?.tiers ?? []} /></td>
                <td>{SOURCE_LABELS[row.source]}{row.sourceDetail?.reason ? ` — ${row.sourceDetail.reason}` : ''}</td>
                <td><button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(row.capability)}>Why?</button></td>
              </tr>
            ))}
          </tbody>
        ))}
      </table>
      {openTraceFor && (traceQuery.data || traceQuery.isError) && (
        <div className="app-panel">
          <h3>{openTraceFor}</h3>
          <ErrorNotice error={traceQuery.error} action="Could not explain this capability" />
          {traceQuery.data && (
            <TraceView
              trace={traceQuery.data.trace}
              tiers={capabilityFor(openTraceFor)?.tiers ?? []}
            />
          )}
          <button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(null)}>Close</button>
        </div>
      )}

      <h2>Overrides</h2>
      {/* Grouped by standing, in force first (c18). Removed is collapsed by default: the record of
          a removal survives for ever (c17), so that group only grows and is the least often wanted. */}
      {STANDING_ORDER.map((standing) => {
        // `?? 'IN_FORCE'` is not defensive noise: an override whose standing the service did not
        // state must still appear. Dropping it would hide an exception from the one screen whose
        // job is listing them, and "in force" is what every override meant before 002.
        const inGroup = account.overrides.filter((o) => (o.standing ?? 'IN_FORCE') === standing)
        if (inGroup.length === 0) return null
        const body = (
          <ul>
            {inGroup.map((o) => (
          <li key={o.id} data-testid={`override-${o.id}`} className={standing === 'IN_FORCE' ? undefined : 'override-row--not-in-force'}>
            {o.capability} — {o.kind} <ValueBadge value={o.value} tiers={capabilityFor(o.capability)?.tiers ?? []} /> — {o.reason} — {o.createdBy}, {o.createdAt} — {effectLabel(o, capabilityFor(o.capability)?.status)}
            {windowSummary(o) && <span className="trace-candidate__window"> · {windowSummary(o)}</span>}
            {confirmingRemoveId === o.id ? (
              <span>
                {/* The known v1 gap, stated where it matters: §8 and future-spec.md §2 permit any
                    caller to lift a compliance suspension. Audited, not prevented. */}
                {o.kind === 'HOLD' ? ' Removal is permitted and audited but not restricted.' : null}
                {removalPreview.data && (
                  <span data-testid="removal-preview">
                    {' '}This returns {o.capability} to{' '}
                    <ValueBadge
                      value={removalPreview.data.value}
                      tiers={capabilityFor(o.capability)?.tiers ?? []}
                    />
                    .
                  </span>
                )}
                {removalPreview.isPending && ' Working out what this returns to…'}
                <button type="button" className="sv-btn" onClick={() => removeMutation.mutate(o.id)}>Confirm removal</button>
                <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingRemoveId(null)}>Cancel</button>
              </span>
            ) : (
              standing !== 'REMOVED' && (
                <button type="button" className="sv-btn--secondary" data-testid={`remove-${o.id}`} onClick={() => { clearActionResults(); setConfirmingRemoveId(o.id) }}>Remove</button>
              )
            )}
          </li>
            ))}
          </ul>
        )
        return (
          <section key={standing} className="override-group" data-testid={`override-group-${standing}`}>
            <h3 className="override-group__heading">{STANDING_HEADINGS[standing]} ({inGroup.length})</h3>
            {standing === 'REMOVED' ? (
              <details>
                <summary>Show removed overrides</summary>
                {body}
              </details>
            ) : (
              body
            )}
          </section>
        )
      })}
      <ErrorNotice error={removalPreview.error} action="Could not work out what this removal returns to" />
      <ErrorNotice error={removeMutation.error} action="Could not remove the override" />
      {removedDecision && removeMutation.data && (
        <div className="app-panel" data-testid="removed-decision">
          <h3>Restored value</h3>
          <TraceView
            trace={removedDecision.decision.trace}
            tiers={capabilityFor(removedDecision.capability)?.tiers ?? []}
          />
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
              {groupByArea(activeCapabilities).map(([area, caps]) => (
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
              valueType={capabilityFor(newCapability)!.valueType}
              tiers={capabilityFor(newCapability)!.tiers}
              value={newValue}
              onChange={setNewValue}
            />
          )}
          <label className="sv-label">Reason
            <input className="sv-field" aria-label="Reason" value={newReason} onChange={(e) => setNewReason(e.target.value)} />
          </label>
          {/* Both optional, and leaving them blank stays the fastest path through the form (c1). */}
          <div className="window-fields">
            <label className="sv-label">Starts on ({SERVICE_CLOCK})
              <input className="sv-field" type="date" aria-label={`Starts on (${SERVICE_CLOCK})`} value={newStartsOn} onChange={(e) => setNewStartsOn(e.target.value)} />
            </label>
            <label className="sv-label">Expires on ({SERVICE_CLOCK})
              <input className="sv-field" type="date" aria-label={`Expires on (${SERVICE_CLOCK})`} value={newExpiresOn} onChange={(e) => setNewExpiresOn(e.target.value)} />
            </label>
          </div>
          <p className="window-sentence" data-testid="window-sentence">{windowSentence(newStartsOn, newExpiresOn)}</p>
          <button type="submit" className="sv-btn" disabled={!newCapability || !newKind || newReason.trim() === ''}>Save override</button>
        </form>
      )}
      {/* Outside the collapsible form, for the same reason as the plan change above. */}
      <ErrorNotice error={addMutation.error} action="Could not add the override" />
      {addedDecision && (
        <div className="app-panel" data-testid="added-decision">
          <h3>Resulting decision</h3>
          <TraceView
            trace={addedDecision.decision.trace}
            tiers={capabilityFor(addedDecision.capability)?.tiers ?? []}
          />
          {addMutation.data && <SaveConfirmation seconds={addMutation.data.changeVisibleEverywhereWithinSeconds} />}
        </div>
      )}
    </div>
  )
}
