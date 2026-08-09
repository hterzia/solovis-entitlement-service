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

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesListRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansListRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
const planEditorRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute /> })
const accountsListRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute })
const accountDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts/$external', component: () => <AccountDetailRoute /> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: CheckerRoute })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: HistoryRoute })

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
