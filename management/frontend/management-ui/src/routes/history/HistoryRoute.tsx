import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { listAuditEvents } from '../../api/audit'
import { queryKeys } from '../../queries/keys'
import { formatAuditValue } from '../../types/value'
import { listCapabilities } from '../../api/capabilities'
import { ErrorNotice } from '../../components/ErrorNotice'

const ENTITY_TYPES = ['CAPABILITY', 'CAPABILITY_TIER', 'PLAN', 'PLAN_ENTITLEMENT', 'ACCOUNT', 'ACCOUNT_PLAN', 'DEFAULT_PLAN', 'OVERRIDE'] as const

export function HistoryRoute() {
  const [account, setAccount] = useState('')
  const [planKey, setPlanKey] = useState('')
  const [actor, setActor] = useState('')
  const [entityType, setEntityType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  // Every filter is the service's to apply; the screen only says what was asked for. Because the
  // filters are the whole query key, changing one starts a fresh accumulation rather than appending
  // to pages fetched under the previous filter.
  const params = {
    account: account || undefined, planKey: planKey || undefined, actor: actor || undefined,
    entityType: entityType || undefined, from: from || undefined, to: to || undefined,
  }
  // Every capability, retired included: history keeps naming a capability long after it is retired
  // (c8), and its rows still have to render a tier by name.
  const capabilitiesQuery = useQuery({
    queryKey: queryKeys.capabilities({ status: 'ALL' }),
    queryFn: () => listCapabilities({ status: 'ALL' }),
  })
  const tiersFor = (capability: string | null) =>
    capability ? capabilitiesQuery.data?.capabilities.find((c) => c.key === capability)?.tiers : undefined

  const query = useInfiniteQuery({
    queryKey: queryKeys.audit(params),
    queryFn: ({ pageParam }) => listAuditEvents({ ...params, cursor: pageParam ?? undefined }),
    initialPageParam: null as string | null,
    // `nextCursor` is null on the last page even when that page is exactly `limit` rows long
    // (contracts/admin-api.md), so page length says nothing about whether another page exists.
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  })
  const events = query.data?.pages.flatMap((page) => page.events) ?? []

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Change history</h1>
      <form onSubmit={(e) => e.preventDefault()}>
        <label className="sv-label">Account
          <input className="sv-field" aria-label="Account" value={account} onChange={(e) => setAccount(e.target.value)} />
        </label>
        <label className="sv-label">Plan
          <input className="sv-field" aria-label="Plan" value={planKey} onChange={(e) => setPlanKey(e.target.value)} />
        </label>
        <label className="sv-label">Actor
          <input className="sv-field" aria-label="Actor" value={actor} onChange={(e) => setActor(e.target.value)} />
        </label>
        <label className="sv-label">Entity type
          <select className="sv-field" aria-label="Entity type" value={entityType} onChange={(e) => setEntityType(e.target.value)}>
            <option value="">All</option>
            {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label className="sv-label">From
          <input className="sv-field" type="date" aria-label="From" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="sv-label">To
          <input className="sv-field" type="date" aria-label="To" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
      </form>

      <ErrorNotice error={query.error} action="Could not load the change history" />

      <table>
        <thead><tr><th>When</th><th>Who</th><th>What changed</th><th>Before</th><th>After</th></tr></thead>
        <tbody>
          {events.map((event) => (
            <tr key={event.seq}>
              <td>{event.occurredAt}</td>
              <td>{event.actor.id} ({event.actor.kind === 'PERSON' ? 'person' : 'system'})</td>
              <td>
                {event.action} {event.entityType} {event.capability ?? event.planKey ?? event.entityId}
                {event.reason && ` — ${event.reason}`}
                {event.affectedAccountCount != null && ` — affected ${event.affectedAccountCount} accounts`}
              </td>
              <td>{formatAuditValue(event.before, tiersFor(event.capability))}</td>
              <td>{formatAuditValue(event.after, tiersFor(event.capability))}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {query.hasNextPage && (
        <button type="button" className="sv-btn--secondary" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          Load more
        </button>
      )}
    </div>
  )
}
