import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { AccountDetailRoute } from './AccountDetailRoute'

describe('AccountDetailRoute', () => {
  it('shows the plan header naming who assigned it and whether a person or a system', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('Pro')).toBeInTheDocument())
    expect(screen.getByText(/billing-sync/)).toBeInTheDocument()
    expect(screen.getByText(/upstream system/)).toBeInTheDocument()
  })

  it('marks each effective entitlement with its source, and shows the override reason inline for a HOLD', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    const row = screen.getByText('reports.monthly').closest('tr')!
    expect(row).toHaveTextContent('HOLD')
    expect(row).toHaveTextContent('Suspended pending billing investigation')
  })

  it('opens the full trace for a capability on request', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Why?' })[0])
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('lists overrides with their current effect in plain language', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText(/Renewal concession/)).toBeInTheDocument())
    expect(screen.getByText(/overridden by a HOLD/)).toBeInTheDocument()
    expect(screen.getByText(/^winning$|— winning$/)).toBeInTheDocument()
  })
})
