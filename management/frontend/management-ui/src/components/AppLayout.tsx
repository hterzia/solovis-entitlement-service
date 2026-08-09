import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { getMeta } from '../api/meta'
import { queryKeys } from '../queries/keys'

const NAV_ITEMS = [
  { to: '/capabilities', label: 'Capabilities' },
  { to: '/plans', label: 'Plans' },
  { to: '/accounts', label: 'Accounts' },
  { to: '/checker', label: 'Checker' },
  { to: '/history', label: 'History' },
] as const

export function AppLayout() {
  const meta = useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })
  const location = useRouterState({ select: (s) => s.location })

  return (
    <div className="app-shell">
      <header className="app-topbar">Entitlement Service — Operator Console</header>
      <div className="app-banner">
        Unauthenticated instance — all actions are open and audited as <code>dev-operator</code>.
        {meta.data ? ` Snapshot v${meta.data.snapshotVersion}.` : null}
      </div>
      <nav className="app-navbar">
        {NAV_ITEMS.map((item) => (
          <Link key={item.to} to={item.to} className={location.pathname.startsWith(item.to) ? 'active' : undefined}>
            {item.label}
          </Link>
        ))}
      </nav>
      <main className="app-canvas">
        <Outlet />
      </main>
    </div>
  )
}
