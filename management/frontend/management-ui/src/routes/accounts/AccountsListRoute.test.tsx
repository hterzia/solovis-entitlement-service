import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
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

  it('loads the next page via cursor when more accounts exist', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/admin/v1/accounts', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        return cursor
          ? HttpResponse.json({ accounts: [{ account: 'acct_page_two', name: null, planKey: 'pro' }], nextCursor: null })
          : HttpResponse.json({ accounts: [{ account: 'acct_page_one', name: null, planKey: 'pro' }], nextCursor: 'acct_next_page' })
      }),
    )

    renderWithProviders(<AccountsListRoute />)
    await waitFor(() => expect(screen.getByRole('link', { name: 'acct_page_one' })).toBeInTheDocument())
    expect(screen.queryByRole('link', { name: 'acct_page_two' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getByRole('link', { name: 'acct_page_two' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('resets to the first page when the search term changes', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/admin/v1/accounts', ({ request }) => {
        const url = new URL(request.url)
        const cursor = url.searchParams.get('cursor')
        if (url.searchParams.get('q')) {
          return HttpResponse.json({ accounts: [{ account: 'acct_searched', name: null, planKey: 'pro' }], nextCursor: null })
        }
        return cursor
          ? HttpResponse.json({ accounts: [{ account: 'acct_page_two', name: null, planKey: 'pro' }], nextCursor: null })
          : HttpResponse.json({ accounts: [{ account: 'acct_page_one', name: null, planKey: 'pro' }], nextCursor: 'acct_next_page' })
      }),
    )

    renderWithProviders(<AccountsListRoute />)
    await waitFor(() => expect(screen.getByRole('link', { name: 'acct_page_one' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getByRole('link', { name: 'acct_page_two' })).toBeInTheDocument())
    await user.type(screen.getByLabelText('Search accounts'), 'a')
    await waitFor(() => expect(screen.getByRole('link', { name: 'acct_searched' })).toBeInTheDocument())
    expect(screen.queryByRole('link', { name: 'acct_page_two' })).not.toBeInTheDocument()
  })
})
