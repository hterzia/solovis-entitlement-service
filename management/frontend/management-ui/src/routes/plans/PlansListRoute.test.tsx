import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
import { PlansListRoute } from './PlansListRoute'
import { listPlans } from '../../api/plans'
import type { Plan } from '../../types/domain'

/**
 * Both seeded plans carry accounts, so archive is disabled for both. These tests need a plan the
 * operator is actually allowed to archive, and a count of how many times the service was asked to
 * archive it — "the confirmation held the request back" is not observable any other way.
 */
function withArchivablePlan(overrides: Partial<Plan> = {}) {
  const plan: Plan = {
    key: 'legacy', name: 'Legacy', status: 'ACTIVE',
    isDefaultForNewAccounts: false, accountCount: 0, entitlementCount: 3, ...overrides,
  }
  let archiveCalls = 0
  server.use(
    http.get('/admin/v1/plans', () => HttpResponse.json({ plans: [plan] })),
    http.post('/admin/v1/plans/:key/archive', () => {
      archiveCalls += 1
      plan.status = 'ARCHIVED'
      return HttpResponse.json(plan)
    }),
  )
  return { plan, archiveCalls: () => archiveCalls }
}

function problem(status: number, type: string, detail: string) {
  return HttpResponse.json({ type, title: type, status, detail }, { status })
}

describe('PlansListRoute', () => {
  it('shows account counts and marks the default plan', async () => {
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByText('71204')).toBeInTheDocument())
    expect(screen.getByTestId('plan-row-free')).toHaveTextContent('Default for new accounts')
  })

  it('disables archive with an explanatory tooltip when the account count is non-zero', async () => {
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())
    const archiveButton = screen.getByRole('button', { name: 'Archive pro' })
    expect(archiveButton).toBeDisabled()
    expect(archiveButton).toHaveAttribute('title', expect.stringMatching(/26890 accounts/))
  })

  it('designates a new default plan only after the operator confirms', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Make pro the default' }))
    expect(screen.getByText('This changes the default plan for all new accounts.')).toBeInTheDocument()
    expect((await listPlans()).plans.find((p) => p.key === 'pro')?.isDefaultForNewAccounts).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(async () => {
      const { plans } = await listPlans()
      expect(plans.find((p) => p.key === 'pro')?.isDefaultForNewAccounts).toBe(true)
    })
  })

  it('abandons the designation on cancel, leaving the default untouched', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Make pro the default' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('button', { name: 'Make pro the default' })).toBeInTheDocument()
    expect((await listPlans()).plans.find((p) => p.key === 'pro')?.isDefaultForNewAccounts).toBe(false)
  })

  it('promises the change is live everywhere once the default is designated', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Make pro the default' }))
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.getByText(/Active everywhere within 60 seconds/)).toBeInTheDocument())
  })

  it('speaks of one account in the singular in the archive tooltip', async () => {
    withArchivablePlan({ accountCount: 1 })
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-legacy')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Archive legacy' }))
      .toHaveAttribute('title', 'Cannot archive — 1 account is on this plan.')
  })

  it('archives nothing until the operator confirms', async () => {
    const spy = withArchivablePlan()
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-legacy')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Archive legacy' }))
    expect(screen.getByText(/Archiving Legacy withdraws it from use/)).toBeInTheDocument()
    expect(spy.archiveCalls()).toBe(0)

    await user.click(screen.getByRole('button', { name: 'Confirm archive' }))
    await waitFor(() => expect(spy.archiveCalls()).toBe(1))
  })

  it('abandons the archive on cancel, leaving the plan untouched', async () => {
    const spy = withArchivablePlan()
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-legacy')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Archive legacy' }))
    await user.click(screen.getByRole('button', { name: 'Cancel archive' }))
    expect(screen.getByRole('button', { name: 'Archive legacy' })).toBeInTheDocument()
    expect(spy.archiveCalls()).toBe(0)
  })

  it('promises the change is live everywhere once a plan is archived', async () => {
    withArchivablePlan()
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-legacy')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Archive legacy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm archive' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
  })

  it('shows the service\'s own words when the archive is refused', async () => {
    withArchivablePlan()
    server.use(http.post('/admin/v1/plans/:key/archive', () =>
      problem(409, 'entitlement/plan-in-use', 'Plan has accounts.')))
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-legacy')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Archive legacy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm archive' }))
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('Could not archive this plan: Plan has accounts.'))
    expect(screen.queryByText('Saved. Active everywhere within 60 seconds.')).not.toBeInTheDocument()
  })

  it('shows the service\'s own words when the default designation is refused', async () => {
    server.use(http.put('/admin/v1/settings/default-plan', () =>
      problem(409, 'entitlement/default-plan-required', 'Plan \'pro\' is archived.')))
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Make pro the default' }))
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('Could not designate the default plan: Plan \'pro\' is archived.'))
  })

  // An unreachable catalogue and an empty one are different facts. Rendering `{query.data && …}`
  // with no error branch made them identical: the screen simply showed its header and no rows.
  it('reports a failed load rather than rendering as though the plans were empty', async () => {
    server.use(http.get('/admin/v1/plans', () => HttpResponse.error()))
    renderWithProviders(<PlansListRoute />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
