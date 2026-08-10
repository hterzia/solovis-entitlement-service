import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../../test/testUtils'
import { db } from '../../test/mocks/handlers'
import { server } from '../../test/mocks/server'
import { CheckerRoute } from './CheckerRoute'

/**
 * Read a <datalist>'s options directly rather than through getByRole('option'): ARIA maps the
 * `option` role through a listbox context, and a <datalist>'s options are not reliably exposed
 * in jsdom. The element is found by id because that id is the contract the `list` attribute names.
 */
function optionsOf(id: string): { value: string; label: string | null }[] {
  const list = document.getElementById(id)
  if (!list) throw new Error(`No <datalist id="${id}"> in the document`)
  return Array.from(list.querySelectorAll('option')).map((o) => ({
    value: (o as HTMLOptionElement).value,
    label: o.textContent,
  }))
}

describe('CheckerRoute suggestions', () => {
  it('suggests every active capability by key, labelled by its display name', async () => {
    renderWithProviders(<CheckerRoute />)

    await waitFor(() => expect(optionsOf('checker-capabilities').length).toBeGreaterThan(0))

    // The operator reads the human name; the field receives the key.
    expect(optionsOf('checker-capabilities')).toEqual(
      expect.arrayContaining([
        { value: 'reports.monthly', label: 'Monthly reports' },
        { value: 'export.parquet', label: 'Parquet export' },
        { value: 'support', label: 'Support level' },
      ]),
    )
  })

  it('omits a retired capability from the suggestions but still accepts it typed', async () => {
    db.capabilities.push({
      key: 'legacy.export',
      area: 'legacy',
      displayName: 'Legacy export',
      description: null,
      valueType: 'SWITCH',
      default: { type: 'SWITCH', enabled: false },
      offValue: null,
      tiers: [],
      status: 'RETIRED',
    })
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)

    await waitFor(() => expect(optionsOf('checker-capabilities').length).toBeGreaterThan(0))
    expect(optionsOf('checker-capabilities').map((o) => o.value)).not.toContain('legacy.export')

    // Suggesting is not constraining: the retired-capability error path (c19) must stay reachable.
    await user.type(screen.getByLabelText('Capability'), 'legacy.export')
    expect(screen.getByLabelText('Capability')).toHaveValue('legacy.export')
  })

  it('suggests accounts matching what has been typed, labelled by name', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)

    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'north')

    // The seeded account is Northwind Capital / acct_9931. The label follows the accounts
    // screen's own convention so a nameless account still reads as something.
    await waitFor(() =>
      expect(optionsOf('checker-accounts')).toEqual([
        { value: 'acct_9931', label: 'Northwind Capital (acct_9931)' },
      ]),
    )
  })

  it('asks the service for no accounts until something is typed', async () => {
    let calls = 0
    server.use(
      http.get('/admin/v1/accounts', () => {
        calls += 1
        return HttpResponse.json({ accounts: [], nextCursor: null })
      }),
    )
    renderWithProviders(<CheckerRoute />)

    // Wait for the page to have settled on a query that *should* run, so "no call yet" means
    // "never called" rather than "not called during the first tick".
    await waitFor(() => expect(optionsOf('checker-capabilities').length).toBeGreaterThan(0))

    expect(calls).toBe(0)
  })
})
