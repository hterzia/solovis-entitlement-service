import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'
import { CapabilitiesListRoute } from './routes/capabilities/CapabilitiesListRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: CapabilitiesListRoute })
const capabilityDetailRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities/$key', component: () => <div>Capability detail</div> })
// AppLayout's nav links to all five top-level screens, and TanStack Router's typed `Link to=`
// only accepts paths registered in this tree — so every screen not yet built keeps the
// Task 6 placeholder here until the task that implements it replaces this file wholesale.
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: () => <div>Plans</div> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: () => <div>Accounts</div> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([indexRoute, capabilitiesRoute, capabilityDetailRoute, plansRoute, accountsRoute, checkerRoute, historyRoute])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
