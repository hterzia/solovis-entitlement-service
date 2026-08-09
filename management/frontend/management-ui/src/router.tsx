import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'
import { CapabilityDetailRoute } from './routes/capabilities/CapabilityDetailRoute'
import { PlansListRoute } from './routes/plans/PlansListRoute'
import { PlanEditorRoute } from './routes/plans/PlanEditorRoute'
import { AccountsListRoute } from './routes/accounts/AccountsListRoute'
import { AccountDetailRoute } from './routes/accounts/AccountDetailRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <CapabilityDetailRoute /> })
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: PlansListRoute })
const planEditorRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans/$key', component: () => <PlanEditorRoute /> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: AccountsListRoute })
const accountDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts/$external', component: () => <AccountDetailRoute /> })
// Still placeholders — kept so AppLayout's typed nav links to the not-yet-built screens compile.
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([
  indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, planEditorRoute,
  accountsRoute, accountDetailRoute, checkerRoute, historyRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
