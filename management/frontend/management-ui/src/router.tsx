import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'
import { PlansListRoute } from './routes/plans/PlansListRoute'
import { PlanEditorRoute } from './routes/plans/PlanEditorRoute'
import { AccountsListRoute } from './routes/accounts/AccountsListRoute'
import { AccountDetailRoute } from './routes/accounts/AccountDetailRoute'
import { CheckerRoute } from './routes/checker/CheckerRoute'
import { HistoryRoute } from './routes/history/HistoryRoute'

// The three roles of ui-screens.md §9 ("Roles (c37 — not implemented in v1)"). Nothing reads
// `requiredRole` today — every screen is fully usable by anyone who can reach the app — but the
// field is present on every route so enabling enforcement later is a guard, not a rewrite.
export type Role = 'VIEWER' | 'ADMINISTRATOR' | 'EXCEPTION_MANAGER'

declare module '@tanstack/react-router' {
  interface StaticDataRouteOption {
    requiredRole?: { read: Role; write?: Role }
  }
}

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })

export const capabilitiesListRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const capabilityDetailRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute />,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const plansListRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const planEditorRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute />,
  staticData: { requiredRole: { read: 'VIEWER', write: 'ADMINISTRATOR' } },
})
export const accountsListRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute,
  staticData: { requiredRole: { read: 'VIEWER', write: 'EXCEPTION_MANAGER' } },
})
export const accountDetailRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/accounts/$external', component: () => <AccountDetailRoute />,
  staticData: { requiredRole: { read: 'VIEWER', write: 'EXCEPTION_MANAGER' } },
})
export const checkerRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/checker', component: CheckerRoute,
  staticData: { requiredRole: { read: 'VIEWER' } },
})
export const historyRoute = createRoute({
  getParentRoute: () => rootRoute, path: '/history', component: HistoryRoute,
  staticData: { requiredRole: { read: 'VIEWER' } },
})

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesListRoute, capabilityDetailRoute, plansListRoute, planEditorRoute,
  accountsListRoute, accountDetailRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
