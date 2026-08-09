import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { PlansListRoute } from './PlansListRoute'
import { listPlans } from '../../api/plans'

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

  it('designates a new default plan', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlansListRoute />)
    await waitFor(() => expect(screen.getByTestId('plan-row-pro')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Make pro the default' }))
    await waitFor(async () => {
      const { plans } = await listPlans()
      expect(plans.find((p) => p.key === 'pro')?.isDefaultForNewAccounts).toBe(true)
    })
  })
})
