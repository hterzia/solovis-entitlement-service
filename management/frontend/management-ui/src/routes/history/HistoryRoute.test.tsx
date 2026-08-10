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
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(5)) // header + 4 events
    const rows = screen.getAllByRole('row')
    expect(rows[1]).toHaveTextContent('a.reyes')
    expect(rows[1]).toHaveTextContent('person')
    // Newest first is the service's ordering; the screen renders it as given, oldest last.
    expect(rows[1]).toHaveTextContent('Investigation closed')
    expect(rows[4]).toHaveTextContent('export.parquet')
  })

  it('marks a system actor as system, not person', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(5))
    const botRow = screen.getByText(/Suspended pending billing investigation/).closest('tr')!
    expect(botRow).toHaveTextContent('billing-bot')
    expect(botRow).toHaveTextContent('system')
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

  it('renders a readable before/after for entity types that never log a bare value', async () => {
    renderWithProviders(<HistoryRoute />)
    // OVERRIDE CREATE logs the whole create request as `after`, not a bare value — the capability
    // and kind it names should still be visible rather than the cell going blank.
    await waitFor(() => expect(screen.getByText(/kind: HOLD/)).toBeInTheDocument())
    // PLAN_ENTITLEMENT UPDATE logs a map of capability→value as `after`.
    expect(screen.getByText(/reports\.monthly: 75/)).toBeInTheDocument()
    // CAPABILITY CREATE logs the whole descriptor as `after`.
    expect(screen.getByText(/displayName: Parquet export/)).toBeInTheDocument()
  })

  it('filters by actor', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(5))
    await user.type(screen.getByLabelText('Actor'), 'billing-bot')
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2)) // header + 1 event
    expect(screen.getByText(/Suspended pending billing investigation/)).toBeInTheDocument()
  })

  it('filters by account', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(5))
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3)) // header + 2 override events
    expect(screen.getByText(/Investigation closed/)).toBeInTheDocument()
  })

  it('filters by plan', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(5))
    await user.type(screen.getByLabelText('Plan'), 'pro')
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2)) // header + 1 plan event
    expect(screen.getByText(/affected 26890 accounts/)).toBeInTheDocument()
  })

  it('filters by entity type', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(5))
    await user.selectOptions(screen.getByLabelText('Entity type'), 'CAPABILITY')
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2)) // header + 1 capability event
    expect(screen.getByText(/displayName: Parquet export/)).toBeInTheDocument()
  })

  it('asks the service for the date range rather than trimming rows itself', async () => {
    const user = userEvent.setup()
    const seen: URL[] = []
    server.use(
      http.get('/admin/v1/audit', ({ request }) => {
        seen.push(new URL(request.url))
        return HttpResponse.json({ events: [], nextCursor: null })
      }),
    )

    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(seen).not.toHaveLength(0))
    await user.type(screen.getByLabelText('From'), '2026-08-01')
    await user.type(screen.getByLabelText('To'), '2026-08-09')
    await waitFor(() => {
      const last = seen[seen.length - 1]
      expect(last.searchParams.get('from')).toBe('2026-08-01')
      expect(last.searchParams.get('to')).toBe('2026-08-09')
    })
  })

  it('shows the service refusal when the history fails to load', async () => {
    server.use(
      http.get('/admin/v1/audit', () =>
        HttpResponse.json(
          { type: 'entitlement/unavailable', title: 'Service unavailable', status: 503, detail: 'The change history is unavailable.' },
          { status: 503 },
        ),
      ),
    )

    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('The change history is unavailable.'))
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load the change history')
  })

  it('offers no edit, delete, or export control', async () => {
    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(5))
    expect(screen.queryByRole('button', { name: /edit|delete|export/i })).not.toBeInTheDocument()
  })

  it('appends the next page to the rows already on screen', async () => {
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
    // The page already read stays on screen — paging forward is not a swap.
    expect(screen.getByText(/page-one-event/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('offers no "Load more" on a last page that happens to be exactly full', async () => {
    const baseEvent = {
      occurredAt: '2026-08-09T16:00:00.000Z', actor: { id: 'a.reyes', kind: 'PERSON' as const },
      source: 'UI' as const, entityType: 'ACCOUNT' as const, action: 'CREATE' as const,
      planKey: null, account: null, capability: null, before: null, after: null,
      reason: null, affectedAccountCount: null,
    }
    server.use(
      http.get('/admin/v1/audit', () =>
        HttpResponse.json({
          events: [
            { ...baseEvent, seq: 600, entityId: 'full-page-event-a' },
            { ...baseEvent, seq: 599, entityId: 'full-page-event-b' },
          ],
          nextCursor: null,
        }),
      ),
    )

    renderWithProviders(<HistoryRoute />)
    await waitFor(() => expect(screen.getByText(/full-page-event-b/)).toBeInTheDocument())
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

  // The audit trail is the one screen where an operator reconstructs what happened, and it was the
  // one screen printing a tier's raw key. Tier display names are immutable in v1 — `addCapabilityTier`
  // only appends and the PATCH route cannot touch them — so rendering the declared name cannot
  // misreport what was recorded.
  it('names a declared tier in the before/after columns rather than its raw key', async () => {
    server.use(
      http.get('/admin/v1/audit', () =>
        HttpResponse.json({
          events: [
            {
              seq: 1, occurredAt: '2026-08-10T00:00:00.000Z', actor: { id: 'dev-operator', kind: 'PERSON' },
              source: 'UI', entityType: 'PLAN_ENTITLEMENT', entityId: 'pro', action: 'UPDATE',
              planKey: 'pro', account: null, capability: 'support',
              before: { type: 'TIER', tier: 'community' },
              after: { type: 'TIER', tier: 'gold' },
              reason: null, affectedAccountCount: 1,
            },
          ],
          nextCursor: null,
        }),
      ),
    )
    renderWithProviders(<HistoryRoute />)

    const row = await screen.findByRole('row', { name: /PLAN_ENTITLEMENT/ })
    expect(row).toHaveTextContent('Community')
    expect(row).toHaveTextContent('Gold')
    expect(row).not.toHaveTextContent('community')
    expect(row).not.toHaveTextContent('gold')
  })
})
