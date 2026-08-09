import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { getAccount } from '../../api/accounts'
import { checkDecision } from '../../api/checker'
import { queryKeys } from '../../queries/keys'
import { ValueBadge } from '../../components/ValueEditor'
import { TraceView } from '../../components/TraceView'
import type { OverrideEffect } from '../../types/domain'

const EFFECT_LABELS: Record<OverrideEffect, string> = {
  WINNING: 'winning',
  OVERRIDDEN_BY_HOLD: 'overridden by a HOLD',
  SUPERSEDED_BY_GRANT: 'superseded by a larger or newer GRANT',
  SUPERSEDED_BY_STRICTER_HOLD: 'superseded by a stricter or newer HOLD',
  NO_EFFECT_PLAN_MORE_GENEROUS: 'no effect — plan is more generous',
  NO_EFFECT_NOT_MORE_RESTRICTIVE: 'no effect — not more restrictive than the result',
}

export function AccountDetailRoute({ external: externalProp }: { external?: string } = {}) {
  const params = useParams({ strict: false }) as { external?: string }
  const external = externalProp ?? params.external!

  const accountQuery = useQuery({ queryKey: queryKeys.account(external), queryFn: () => getAccount(external) })
  const [openTraceFor, setOpenTraceFor] = useState<string | null>(null)
  const traceQuery = useQuery({
    queryKey: queryKeys.check({ account: external, capability: openTraceFor ?? undefined }),
    queryFn: () => checkDecision({ account: external, capability: openTraceFor! }),
    enabled: openTraceFor !== null,
  })

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
          <TraceView trace={traceQuery.data.trace} />
          <button type="button" className="sv-btn--secondary" onClick={() => setOpenTraceFor(null)}>Close</button>
        </div>
      )}

      <h2>Overrides</h2>
      <ul>
        {account.overrides.map((o) => (
          <li key={o.id}>
            {o.kind} <ValueBadge value={o.value} tiers={[]} /> — {o.reason} — {o.createdBy}, {o.createdAt} — {EFFECT_LABELS[o.effectNow]}
          </li>
        ))}
      </ul>
    </div>
  )
}
