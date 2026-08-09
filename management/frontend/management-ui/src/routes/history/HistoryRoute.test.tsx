import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
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

  it('offers no edit, delete, or export control', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(4))
    expect(screen.queryByRole('button', { name: /edit|delete|export/i })).not.toBeInTheDocument()
  })

  it('loads the next page via cursor when more events exist', async () => {
    const user = userEvent.setup()
    const baseEvent = {
      occurredAt: '2026-08-09T16:00:00.000Z', actor: { id: 'a.reyes', kind: 'PERSON' as const },
      source: 'UI' as const, entityType: 'ACCOUNT' as const, action: 'CREATE' as const,
      planKey: null, account: null, capability: null, before: null, after: null,
      reason: null, affectedAccountCount: null,
    }
    const page1Event = { ...baseEvent, seq: 500, entityId: 'page-one-event' }
    const page2Event = { ...baseEvent, seq: 400, entityId: 'page-two-event' }

    server.use(
      http.get('/admin/v1/audit', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        return cursor
          ? HttpResponse.json({ events: [page2Event], nextCursor: null })
          : HttpResponse.json({ events: [page1Event], nextCursor: 'aud_next_page' })
      }),
    )

    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByText(/page-one-event/)).toBeInTheDocument())
    expect(screen.queryByText(/page-two-event/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getByText(/page-two-event/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('resets to the first page when a filter changes', async () => {
    const user = userEvent.setup()
    const baseEvent = {
      occurredAt: '2026-08-09T16:00:00.000Z', actor: { id: 'a.reyes', kind: 'PERSON' as const },
      source: 'UI' as const, entityType: 'ACCOUNT' as const, action: 'CREATE' as const,
      planKey: null, account: null, capability: null, before: null, after: null,
      reason: null, affectedAccountCount: null,
    }
    server.use(
      http.get('/admin/v1/audit', ({ request }) => {
        const url = new URL(request.url)
        const cursor = url.searchParams.get('cursor')
        const actorFilter = url.searchParams.get('actor')
        if (actorFilter) return HttpResponse.json({ events: [{ ...baseEvent, seq: 700, entityId: 'filtered-event' }], nextCursor: null })
        return cursor
          ? HttpResponse.json({ events: [{ ...baseEvent, seq: 400, entityId: 'page-two-event' }], nextCursor: null })
          : HttpResponse.json({ events: [{ ...baseEvent, seq: 500, entityId: 'page-one-event' }], nextCursor: 'aud_next_page' })
      }),
    )

    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByText(/page-one-event/)).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getByText(/page-two-event/)).toBeInTheDocument())
    await user.type(screen.getByLabelText('Actor'), 'a.reyes')
    await waitFor(() => expect(screen.getByText(/filtered-event/)).toBeInTheDocument())
    expect(screen.queryByText(/page-two-event/)).not.toBeInTheDocument()
  })
})
