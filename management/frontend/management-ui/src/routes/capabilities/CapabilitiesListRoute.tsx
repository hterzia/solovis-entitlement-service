import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { listCapabilities } from '../../api/capabilities'
import { queryKeys } from '../../queries/keys'
import { CapabilityTree } from '../../components/CapabilityTree'
import { ValueBadge } from '../../components/ValueEditor'
import { CapabilityCreateForm } from './CapabilityCreateForm'

export function CapabilitiesListRoute() {
  const [showRetired, setShowRetired] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
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
      <button type="button" className="sv-btn" onClick={() => setShowCreate((v) => !v)}>
        {showCreate ? 'Cancel' : 'Declare a capability'}
      </button>
      {showCreate && <CapabilityCreateForm onCreated={() => setShowCreate(false)} />}
      {query.data && (
        <CapabilityTree
          items={query.data.capabilities}
          emptyMessage="No capabilities found."
          renderRow={(cap) => (
            <Link to="/capabilities/$key" params={{ key: cap.key }} className="sv-link" style={cap.status === 'RETIRED' ? { opacity: 0.5 } : undefined}>
              {cap.displayName} <ValueBadge value={cap.default} tiers={cap.tiers} /> {cap.status === 'RETIRED' && <span> (retired)</span>}
            </Link>
          )}
        />
      )}
    </div>
  )
}
