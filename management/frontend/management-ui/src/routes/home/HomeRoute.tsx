import { Link } from '@tanstack/react-router'

export function HomeRoute() {
  return (
    <div className="app-panel">
      <h1 className="app-page-title">Entitlement Service</h1>
      <p>Answers, for any account and any capability, whether it is allowed, what the value is, and how that was decided.</p>
      <ul>
        <li><Link to="/capabilities" className="sv-link">Capability registry</Link> — declare and retire capabilities.</li>
        <li><Link to="/plans" className="sv-link">Plans</Link> — edit baseline entitlements for every account on a plan.</li>
        <li><Link to="/accounts" className="sv-link">Accounts</Link> — view an account's effective entitlements and overrides.</li>
        <li><Link to="/checker" className="sv-link">Checker</Link> — see the decision and full explanation for any account and capability.</li>
        <li><Link to="/history" className="sv-link">Change history</Link> — every change, filterable by account, plan and actor.</li>
      </ul>
    </div>
  )
}
