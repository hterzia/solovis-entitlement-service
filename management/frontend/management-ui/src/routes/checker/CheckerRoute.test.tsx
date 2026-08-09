import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CheckerRoute } from './CheckerRoute'
import { db } from '../../test/mocks/handlers'

describe('CheckerRoute', () => {
  it('checks an account and capability and renders the trace', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
    expect(screen.getByText(/Snapshot v48211/)).toBeInTheDocument()
  })

  it('resolves an override reference to its account and capability', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Override reference'), 'ovr_7788')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('renders "No such account" as an error, never a denial', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_does_not_exist')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText('No such account.')).toBeInTheDocument())
    expect(screen.queryByText(/allowed: false/i)).not.toBeInTheDocument()
  })

  it('renders "That capability is retired and is no longer evaluated" for a retired capability', async () => {
    db.capabilities.find((c) => c.key === 'reports.monthly')!.status = 'RETIRED'
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText('That capability is retired and is no longer evaluated.')).toBeInTheDocument())
  })

  it('copies the rendered explanation as text', async () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn() }, configurable: true })
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Copy explanation' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Copy explanation' }))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('Most restrictive HOLD'))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('j.okafor'))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('Renewal concession'))
  })
})
