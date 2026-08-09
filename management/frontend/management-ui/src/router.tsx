import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { AppLayout } from './components/AppLayout'
import { HomeRoute } from './routes/home/HomeRoute'

const rootRoute = createRootRoute({ component: AppLayout })

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute })
const capabilitiesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/capabilities', component: () => <div>Capabilities</div> })
const plansRoute = createRoute({ getParentRoute: () => rootRoute, path: '/plans', component: () => <div>Plans</div> })
const accountsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accounts', component: () => <div>Accounts</div> })
const checkerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/checker', component: () => <div>Checker</div> })
const historyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/history', component: () => <div>History</div> })

const routeTree = rootRoute.addChildren([indexRoute, capabilitiesRoute, plansRoute, accountsRoute, checkerRoute, historyRoute])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
