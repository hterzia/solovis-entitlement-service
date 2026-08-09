import { describe, expect, it } from 'vitest'
import {
  capabilitiesListRoute, capabilityDetailRoute, plansListRoute, planEditorRoute,
  accountsListRoute, accountDetailRoute, checkerRoute, historyRoute,
} from './router'

describe('router requiredRole metadata', () => {
  it('marks every write-capable screen as requiring its stated role', () => {
    expect(capabilitiesListRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(capabilityDetailRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(plansListRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(planEditorRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'ADMINISTRATOR' })
    expect(accountsListRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'EXCEPTION_MANAGER' })
    expect(accountDetailRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER', write: 'EXCEPTION_MANAGER' })
  })

  it('marks the read-only screens with no write role', () => {
    expect(checkerRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER' })
    expect(historyRoute.options.staticData?.requiredRole).toEqual({ read: 'VIEWER' })
  })
})
