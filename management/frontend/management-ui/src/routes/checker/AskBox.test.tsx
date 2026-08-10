import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
import { AskBox } from './AskBox'

function problem(status: number, type: string, detail: string) {
  return HttpResponse.json({ type, title: type, status, detail }, { status })
}

describe('AskBox', () => {
  it('asks a question and hands the resolved triple to the caller', async () => {
    const user = userEvent.setup()
    const onResolved = vi.fn()
    renderWithProviders(<AskBox onResolved={onResolved} />)

    await waitFor(() => expect(screen.getByLabelText('Ask')).not.toBeDisabled())
    await user.type(screen.getByLabelText('Ask'), 'Can Acme export parquet?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    await waitFor(() => expect(onResolved).toHaveBeenCalledWith('acct_9931', 'reports.monthly', undefined))
    expect(screen.getByText(/Understood as:/)).toBeInTheDocument()
    expect(screen.getByText('Northwind Capital')).toBeInTheDocument()
  })

  it('shows the date understood in words when the answer used one', async () => {
    server.use(
      http.post('/admin/v1/check/ask', () =>
        HttpResponse.json({
          status: 'ANSWERED',
          interpretation: {
            account: { external: 'acct_9931', name: 'Northwind Capital' },
            capability: 'reports.monthly',
            asAt: '2026-07-15',
            dateMention: 'last month',
          },
          result: { account: 'acct_9931', capability: 'reports.monthly', allowed: true },
        }),
      ),
    )
    const user = userEvent.setup()
    const onResolved = vi.fn()
    renderWithProviders(<AskBox onResolved={onResolved} />)

    await waitFor(() => expect(screen.getByLabelText('Ask')).not.toBeDisabled())
    await user.type(screen.getByLabelText('Ask'), 'How many reports could Acme export last month?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    await waitFor(() => expect(onResolved).toHaveBeenCalledWith('acct_9931', 'reports.monthly', '2026-07-15'))
    expect(screen.getByText(/as at 15 July 2026/)).toBeInTheDocument()
  })

  it('lists candidates on CLARIFY and a pick resolves with the pair, date included', async () => {
    server.use(
      http.post('/admin/v1/check/ask', () =>
        HttpResponse.json({
          status: 'CLARIFY',
          interpretation: { accountMention: 'Acme', asAt: '2026-07-15', dateMention: 'last month' },
          accountCandidates: [
            { external: 'acme-us', name: 'Acme US' },
            { external: 'acme-emea', name: 'Acme EMEA' },
          ],
          capabilityCandidates: ['export.parquet'],
        }),
      ),
    )
    const user = userEvent.setup()
    const onResolved = vi.fn()
    renderWithProviders(<AskBox onResolved={onResolved} />)

    await waitFor(() => expect(screen.getByLabelText('Ask')).not.toBeDisabled())
    await user.type(screen.getByLabelText('Ask'), 'Can Acme export parquet last month?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    const pick = await screen.findByRole('button', { name: 'Acme US (acme-us)' })
    await user.click(pick)

    // No capability was named in the interpretation for this scenario — the account pick still
    // carries the resolved date through, matching what a CLARIFY on account alone looks like.
    expect(onResolved).toHaveBeenCalledWith('acme-us', '', '2026-07-15')
  })

  it('renders a NO_MATCH detail as a plain statement, never as a decision', async () => {
    server.use(
      http.post('/admin/v1/check/ask', () =>
        HttpResponse.json({
          status: 'NO_MATCH',
          unmatched: { accountMention: 'Acme Ltd' },
          detail: "No account matching 'Acme Ltd'.",
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<AskBox onResolved={vi.fn()} />)

    await waitFor(() => expect(screen.getByLabelText('Ask')).not.toBeDisabled())
    await user.type(screen.getByLabelText('Ask'), 'Can Acme Ltd export parquet?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent("No account matching 'Acme Ltd'.")
    expect(notice).not.toHaveTextContent(/\ballowed\b/i)
  })

  it('renders a RETIRED_CAPABILITY detail as a plain statement', async () => {
    server.use(
      http.post('/admin/v1/check/ask', () =>
        HttpResponse.json({
          status: 'RETIRED_CAPABILITY',
          interpretation: { capability: 'export.csv' },
          detail: "Capability 'export.csv' is retired and no longer evaluable.",
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<AskBox onResolved={vi.fn()} />)

    await waitFor(() => expect(screen.getByLabelText('Ask')).not.toBeDisabled())
    await user.type(screen.getByLabelText('Ask'), 'Can Acme still use export.csv?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/retired/i)
  })

  it('disables the box when the feature is off, without ever sending a question', async () => {
    server.use(
      http.get('/admin/v1/meta', () =>
        HttpResponse.json({
          changeVisibleEverywhereWithinSeconds: 60, answerReuseMaxSeconds: 10,
          snapshotVersion: 1, capabilityAreas: [], askEnabled: false,
        }),
      ),
    )
    renderWithProviders(<AskBox onResolved={vi.fn()} />)

    await waitFor(() => expect(screen.getByLabelText('Ask')).toBeDisabled())
    expect(screen.getByText('Ask is unavailable — use the pickers below.')).toBeInTheDocument()
  })

  it('shows a 503 as the service unavailable message, not a generic error', async () => {
    server.use(
      http.post('/admin/v1/check/ask', () =>
        problem(503, 'entitlement/ask-unavailable',
          'The plain-English checker is not available right now; use the account and capability pickers.'),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<AskBox onResolved={vi.fn()} />)

    await waitFor(() => expect(screen.getByLabelText('Ask')).not.toBeDisabled())
    await user.type(screen.getByLabelText('Ask'), 'Can Acme export parquet?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/not available right now/)
  })
})
