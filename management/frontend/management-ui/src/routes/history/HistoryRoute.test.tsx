import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { HistoryRoute } from './HistoryRoute'

describe('HistoryRoute', () => {
  it('lists events newest first with who, what, before and after', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(4)) // header + 3 events
    const rows = screen.getAllByRole('row')
    expect(rows[1]).toHaveTextContent('a.reyes')
    expect(rows[1]).toHaveTextContent('person')
  })

  it('shows the affected-account count on a plan-entitlement row', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByText(/affected 26890 accounts/)).toBeInTheDocument())
  })

  it('shows the reason on an override row and who removed it', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByText(/Investigation closed/)).toBeInTheDocument())
    const removalRow = screen.getByText(/Investigation closed/).closest('tr')!
    expect(removalRow).toHaveTextContent('a.reyes')
    expect(removalRow).toHaveTextContent('REMOVE')
  })

  it('filters by actor', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(4))
    await user.type(screen.getByLabelText('Actor'), 'billing-bot')
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2)) // header + 1 event
    expect(screen.getByText(/Suspended pending billing investigation/)).toBeInTheDocument()
  })

  it('offers no edit, delete, or export control', () => {
    renderWithProviders(<HistoryRoute />)
    expect(screen.queryByRole('button', { name: /edit|delete|export/i })).not.toBeInTheDocument()
  })
})
