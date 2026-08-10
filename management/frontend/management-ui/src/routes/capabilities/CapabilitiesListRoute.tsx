import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { listCapabilities } from '../../api/capabilities'
import { queryKeys } from '../../queries/keys'
import { CapabilityTree } from '../../components/CapabilityTree'
import { ValueBadge } from '../../components/ValueEditor'
import { CapabilityCreateForm } from './CapabilityCreateForm'
import { ErrorNotice } from '../../components/ErrorNotice'

export function CapabilitiesListRoute() {
  const [showRetired, setShowRetired] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const [createPending, setCreatePending] = useState(false)
  const status = showRetired ? 'ALL' : 'ACTIVE'
  const query = useQuery({
    queryKey: queryKeys.capabilities({ status }),
    queryFn: () => listCapabilities({ status }),
  })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Capabilities</h1>
      <label className="sv-label">
        <input type="checkbox" checked={showRetired} onChange={(e) => setShowRetired(e.target.checked)} />
        {' '}Show retired
      </label>
      <button
        type="button"
        className="sv-btn"
        disabled={createPending}
        title={createPending ? 'Waiting for the service to answer — the capability may already have been declared.' : undefined}
        onClick={() => setShowCreate((v) => !v)}
      >
        {showCreate ? 'Cancel' : 'Declare a capability'}
      </button>
      {showCreate && <CapabilityCreateForm onCreated={() => setShowCreate(false)} onPendingChange={setCreatePending} />}
      {/* An unreachable registry and an empty one must not look alike. */}
      <ErrorNotice error={query.error} action="Could not load the capabilities" />
      {query.data && (
        <CapabilityTree
          items={query.data.capabilities}
          emptyMessage="No capabilities found."
          renderRow={(cap) => {
            // contracts/ui-screens.md Screen 1 — key, display name, value type, default,
            // off-value, status, on one wrapping line so several hundred rows stay scannable.
            const retired = cap.status === 'RETIRED'
            return (
              <div
                data-testid={`capability-row-${cap.key}`}
                data-retired={retired || undefined}
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'baseline',
                  gap: 'var(--sv-space-8)',
                  opacity: retired ? 0.5 : undefined,
                }}
              >
                <Link to="/capabilities/$key" params={{ key: cap.key }} className="sv-link">{cap.displayName}</Link>
                <span style={{ color: 'var(--sv-slate-500)' }}>{cap.key}</span>
                <span className="sv-eyebrow" data-testid="capability-value-type">{cap.valueType}</span>
                <span data-testid="capability-default">
                  <span className="sv-eyebrow">Default </span>
                  <ValueBadge value={cap.default} tiers={cap.tiers} />
                </span>
                <span data-testid="capability-off-value">
                  <span className="sv-eyebrow">Off-value </span>
                  {cap.offValue
                    ? <ValueBadge value={cap.offValue} tiers={cap.tiers} />
                    : <span style={{ color: 'var(--sv-slate-500)' }}>None</span>}
                </span>
                <span className="sv-eyebrow" data-testid="capability-status">{retired ? 'Retired' : 'Active'}</span>
              </div>
            )
          }}
        />
      )}
    </div>
  )
}
