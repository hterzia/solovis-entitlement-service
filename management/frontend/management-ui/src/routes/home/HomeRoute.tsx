import { Link } from '@tanstack/react-router'

export function HomeRoute() {
  return (
    <div className="app-panel">
      <h1 className="app-page-title">Entitlement Service</h1>
      <ul>
        <li><Link to="/capabilities" className="sv-link">Capability Manager</Link></li>
        <li><Link to="/plans" className="sv-link">Plan Manager</Link></li>
        <li><Link to="/accounts" className="sv-link">Account Manager</Link></li>
        <li><Link to="/checker" className="sv-link">Decision Lookup</Link></li>
        <li><Link to="/history" className="sv-link">Audit History</Link></li>
      </ul>
    </div>
  )
}
