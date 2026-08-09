import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listAuditEvents } from '../../api/audit'
import { queryKeys } from '../../queries/keys'
import { formatAuditValue } from '../../types/value'

const ENTITY_TYPES = ['CAPABILITY', 'CAPABILITY_TIER', 'PLAN', 'PLAN_ENTITLEMENT', 'ACCOUNT', 'ACCOUNT_PLAN', 'DEFAULT_PLAN', 'OVERRIDE'] as const

export function HistoryRoute() {
  const [account, setAccount] = useState('')
  const [planKey, setPlanKey] = useState('')
  const [actor, setActor] = useState('')
  const [entityType, setEntityType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [cursor, setCursor] = useState<string | undefined>(undefined)

  function updateFilter(setter: (value: string) => void) {
    return (value: string) => { setter(value); setCursor(undefined) }
  }

  const params = {
    account: account || undefined, planKey: planKey || undefined, actor: actor || undefined,
    entityType: entityType || undefined, from: from || undefined, to: to || undefined, cursor,
  }
  const query = useQuery({ queryKey: queryKeys.audit(params), queryFn: () => listAuditEvents(params) })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Change history</h1>
      <form onSubmit={(e) => e.preventDefault()}>
        <label className="sv-label">Account
          <input className="sv-field" aria-label="Account" value={account} onChange={(e) => updateFilter(setAccount)(e.target.value)} />
        </label>
        <label className="sv-label">Plan
          <input className="sv-field" aria-label="Plan" value={planKey} onChange={(e) => updateFilter(setPlanKey)(e.target.value)} />
        </label>
        <label className="sv-label">Actor
          <input className="sv-field" aria-label="Actor" value={actor} onChange={(e) => updateFilter(setActor)(e.target.value)} />
        </label>
        <label className="sv-label">Entity type
          <select className="sv-field" aria-label="Entity type" value={entityType} onChange={(e) => updateFilter(setEntityType)(e.target.value)}>
            <option value="">All</option>
            {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label className="sv-label">From
          <input className="sv-field" type="date" aria-label="From" value={from} onChange={(e) => updateFilter(setFrom)(e.target.value)} />
        </label>
        <label className="sv-label">To
          <input className="sv-field" type="date" aria-label="To" value={to} onChange={(e) => updateFilter(setTo)(e.target.value)} />
        </label>
      </form>

      <table>
        <thead><tr><th>When</th><th>Who</th><th>What changed</th><th>Before</th><th>After</th></tr></thead>
        <tbody>
          {query.data?.events.map((event) => (
            <tr key={event.seq}>
              <td>{event.occurredAt}</td>
              <td>{event.actor.id} ({event.actor.kind === 'PERSON' ? 'person' : 'system'})</td>
              <td>
                {event.action} {event.entityType} {event.capability ?? event.planKey ?? event.entityId}
                {event.reason && ` — ${event.reason}`}
                {event.affectedAccountCount != null && ` — affected ${event.affectedAccountCount} accounts`}
              </td>
              <td>{formatAuditValue(event.before)}</td>
              <td>{formatAuditValue(event.after)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {query.data?.nextCursor && (
        <button type="button" className="sv-btn--secondary" onClick={() => setCursor(query.data!.nextCursor!)}>
          Load more
        </button>
      )}
    </div>
  )
}
