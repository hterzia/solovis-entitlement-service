import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { AccountsListRoute } from './AccountsListRoute'

describe('AccountsListRoute', () => {
  it('lists accounts and links to the account detail route', async () => {
    renderWithProviders(<AccountsListRoute />)
    await waitFor(() => expect(screen.getByRole('link', { name: /Northwind Capital/i })).toBeInTheDocument())
  })

  it('searches by account or name', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountsListRoute />)
    await waitFor(() => expect(screen.getByRole('link', { name: /Northwind Capital/i })).toBeInTheDocument())
    await user.type(screen.getByLabelText('Search accounts'), 'no-such-account')
    await waitFor(() => expect(screen.queryByRole('link', { name: /Northwind Capital/i })).not.toBeInTheDocument())
  })

  it('creates a new account, assigned to the default plan', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountsListRoute />)
    // The form renders immediately (it isn't gated behind the accounts list query), but
    // RouterProvider's initial route match is still async — wait for it before interacting.
    await waitFor(() => expect(screen.getByLabelText('New account external id')).toBeInTheDocument())
    await user.type(screen.getByLabelText('New account external id'), 'acct_5001')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    await waitFor(() => expect(screen.getByRole('link', { name: /acct_5001/i })).toBeInTheDocument())
  })
})
