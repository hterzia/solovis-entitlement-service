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
  })

  it('blocks override submission until a reason is given', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    // AccountDetailRoute renders "Loading…" until both the router's initial match and the
    // account query settle, so the first interaction must wait for real content, not assume it.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add override' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    expect(screen.getByRole('button', { name: 'Save override' })).toBeDisabled()
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    expect(screen.getByRole('button', { name: 'Save override' })).toBeEnabled()
  })

  it('creates an override and immediately shows the resulting trace', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add override' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    await user.click(screen.getByRole('button', { name: 'Save override' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('removes an override, warning that a HOLD removal is not itself restricted', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2))
    const holdRemoveButton = screen.getByTestId('remove-ovr_7788')
    await user.click(holdRemoveButton)
    expect(screen.getByText(/audited but not restricted/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm removal' }))
    await waitFor(() => expect(screen.queryByTestId('remove-ovr_7788')).not.toBeInTheDocument())
  })

  it('reassigns the plan and confirms how many overrides are retained', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change plan' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    await user.selectOptions(screen.getByLabelText('New plan'), 'free')
    await user.click(screen.getByLabelText('A person'))
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    await waitFor(() => expect(screen.getByText(/2 overrides are retained/)).toBeInTheDocument())
  })
})
