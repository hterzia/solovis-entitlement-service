import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../test/testUtils'
import { server } from '../test/mocks/server'
import { db } from '../test/mocks/handlers'
import { makeAccount } from '../test/mocks/fixtures'
import { AccountDetailRoute } from './accounts/AccountDetailRoute'
import { CheckerRoute } from './checker/CheckerRoute'
import { HistoryRoute } from './history/HistoryRoute'

/**
 * The 002 additions to screens 3, 4 and 5.
 *
 * <p>MSW-backed, so these prove the screens render what the service says — never that the service
 * says it. The window form, the grouped list, the past-date banner and the capability filter are
 * proved end to end in `e2e/`, which is the only place SPA and service are both real.
 */
describe('002 — windows on screen 3', () => {
  function accountWithStandings() {
    const account = makeAccount()
    account.overrides = [
      { id: 'ovr_now', capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 200 },
        reason: 'in force', createdBy: 'a.reyes', createdAt: '2026-01-01T00:00:00.000Z',
        effectNow: 'WINNING', startsOn: null, expiresOn: '2026-12-31', standing: 'IN_FORCE' },
      { id: 'ovr_soon', capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 300 },
        reason: 'pilot next quarter', createdBy: 'a.reyes', createdAt: '2026-01-01T00:00:00.000Z',
        effectNow: null, startsOn: '2027-01-01', expiresOn: null, standing: 'PENDING' },
      { id: 'ovr_done', capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 400 },
        reason: 'last year', createdBy: 'a.reyes', createdAt: '2025-01-01T00:00:00.000Z',
        effectNow: null, startsOn: null, expiresOn: '2025-12-31', standing: 'ENDED' },
      { id: 'ovr_gone', capability: 'reports.monthly', kind: 'HOLD', value: { type: 'QUANTITY', amount: 0 },
        reason: 'lifted', createdBy: 'a.reyes', createdAt: '2025-06-01T00:00:00.000Z',
        effectNow: null, startsOn: null, expiresOn: null, standing: 'REMOVED' },
    ] as (typeof account)['overrides']
    return account
  }

  it('groups overrides by standing, in force first, with removed collapsed', async () => {
    const account = accountWithStandings()
    server.use(http.get('/admin/v1/accounts/:external', () => HttpResponse.json(account)))
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    const inForce = await screen.findByTestId('override-group-IN_FORCE')
    expect(within(inForce).getByTestId('override-ovr_now')).toBeInTheDocument()
    expect(within(await screen.findByTestId('override-group-PENDING')).getByTestId('override-ovr_soon')).toBeInTheDocument()
    expect(within(await screen.findByTestId('override-group-ENDED')).getByTestId('override-ovr_done')).toBeInTheDocument()

    // Present, but behind a disclosure: the removed group grows without bound (c17).
    const removed = await screen.findByTestId('override-group-REMOVED')
    expect(within(removed).getByText('Show removed overrides')).toBeInTheDocument()
    expect(within(removed).getByTestId('override-ovr_gone')).toBeInTheDocument()
  })

  it('shows the window an override was granted under', async () => {
    server.use(http.get('/admin/v1/accounts/:external', () => HttpResponse.json(accountWithStandings())))
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    expect(await screen.findByTestId('override-ovr_soon')).toHaveTextContent('from 2027-01-01')
    expect(await screen.findByTestId('override-ovr_done')).toHaveTextContent('to 2025-12-31 inclusive')
  })

  it('offers no removal for an override that is already removed', async () => {
    server.use(http.get('/admin/v1/accounts/:external', () => HttpResponse.json(accountWithStandings())))
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    await screen.findByTestId('override-ovr_gone')
    expect(screen.queryByTestId('remove-ovr_gone')).not.toBeInTheDocument()
    expect(screen.getByTestId('remove-ovr_now')).toBeInTheDocument()
  })

  it('says in words what the dates mean, before anything is saved (c6)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    await user.click(await screen.findByRole('button', { name: 'Add override' }))
    expect(screen.getByTestId('window-sentence')).toHaveTextContent(
      'In force from now until it is removed (US Eastern).')

    await user.type(await screen.findByLabelText('Starts on (US Eastern)'), '2027-10-01')
    await user.type(await screen.findByLabelText('Expires on (US Eastern)'), '2027-12-31')

    expect(screen.getByTestId('window-sentence')).toHaveTextContent(
      'In force from 2027-10-01 to 2027-12-31 inclusive (US Eastern).')
  })

  it('sends the dates the operator entered', async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | null = null
    server.use(http.post('/admin/v1/accounts/:external/overrides', async ({ request }) => {
      sent = (await request.json()) as Record<string, unknown>
      return HttpResponse.json({ overrideId: 'ovr_new', decision: { allowed: true, value: { type: 'QUANTITY', amount: 1 }, trace: db.account ? undefined : undefined }, snapshotVersion: 1, changeVisibleEverywhereWithinSeconds: 60 }, { status: 201 })
    }))
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    await user.click(await screen.findByRole('button', { name: 'Add override' }))
    await user.selectOptions(await screen.findByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(await screen.findByLabelText('Kind'), 'GRANT')
    await user.type(await screen.findByLabelText('Reason'), 'Q4 pilot')
    await user.type(await screen.findByLabelText('Starts on (US Eastern)'), '2027-10-01')
    await user.type(await screen.findByLabelText('Expires on (US Eastern)'), '2027-12-31')
    await user.click(await screen.findByRole('button', { name: 'Save override' }))

    await waitFor(() => expect(sent).not.toBeNull())
    expect(sent).toMatchObject({ startsOn: '2027-10-01', expiresOn: '2027-12-31' })
  })
})

describe('002 — the past on screen 4', () => {
  const PAST_ANSWER = {
    asAt: '2027-03-14',
    account: 'acct_9931',
    capability: 'reports.monthly',
    allowed: true,
    value: { type: 'QUANTITY', amount: 50 },
    snapshotVersion: 0,
    evaluatedAt: '2027-03-15T00:00:00.000Z',
    trace: {
      baseline: { source: 'PLAN', planKey: 'pro', value: { type: 'QUANTITY', amount: 50 }, note: 'Plan pro sets 50.' },
      grants: [{
        overrideId: 'ovr_old', value: { type: 'QUANTITY', amount: 200 }, reason: 'Q1 pilot',
        createdBy: 'a.reyes', createdAt: '2027-01-01T00:00:00.000Z',
        outcome: 'NOT_IN_FORCE_ENDED', startsOn: null, expiresOn: '2027-02-28', notInForceSince: '2027-03-01',
      }],
      grantStep: { applied: false, why: 'NO_GRANTS_IN_FORCE' },
      holds: [],
      holdStep: { applied: false, why: 'NO_HOLDS' },
      result: { value: { type: 'QUANTITY', amount: 50 }, allowed: true, allowedReason: 'DIFFERS_FROM_OFF_VALUE' },
    },
  }

  it('sends the date, banners that it is showing the past, and offers the way back', async () => {
    const user = userEvent.setup()
    const asked: string[] = []
    server.use(http.get('/admin/v1/check', ({ request }) => {
      const asAt = new URL(request.url).searchParams.get('asAt')
      asked.push(asAt ?? 'today')
      return HttpResponse.json(asAt ? PAST_ANSWER : { ...PAST_ANSWER, asAt: undefined })
    }))
    renderWithProviders(<CheckerRoute />)

    await user.type(await screen.findByLabelText('Account'), 'acct_9931')
    await user.type(await screen.findByLabelText('Capability'), 'reports.monthly')
    await user.type(await screen.findByLabelText('As at (US Eastern)'), '2027-03-14')
    await user.click(await screen.findByRole('button', { name: 'Check' }))

    const banner = await screen.findByText(/Showing/)
    expect(banner).toHaveTextContent('2027-03-14')
    expect(banner).toHaveTextContent('not today')
    expect(asked).toContain('2027-03-14')

    await user.click(await screen.findByRole('button', { name: 'Show the current answer' }))
    await waitFor(() => expect(asked).toContain('today'))
    await waitFor(() => expect(screen.queryByText(/Showing/)).not.toBeInTheDocument())
  })

  it('renders a not-in-force grant with the date it ended, rather than omitting it (c20)', async () => {
    const user = userEvent.setup()
    server.use(http.get('/admin/v1/check', () => HttpResponse.json(PAST_ANSWER)))
    renderWithProviders(<CheckerRoute />)

    await user.type(await screen.findByLabelText('Account'), 'acct_9931')
    await user.type(await screen.findByLabelText('Capability'), 'reports.monthly')
    await user.click(await screen.findByRole('button', { name: 'Check' }))

    const row = await screen.findByTestId('grant-ovr_old')
    expect(row).toHaveTextContent('not in force — ended')
    expect(row).toHaveTextContent('ended after 2027-02-28')
    expect(row).toHaveAttribute('data-not-in-force', 'true')
    expect(await screen.findByText(/No GRANT is in force/)).toBeInTheDocument()
  })

  it('states a retirement that happened after the date asked about (c28)', async () => {
    const user = userEvent.setup()
    server.use(http.get('/admin/v1/check', () =>
      HttpResponse.json({ ...PAST_ANSWER, capabilityRetiredSince: '2027-06-01T00:00:00.000Z' })))
    renderWithProviders(<CheckerRoute />)

    await user.type(await screen.findByLabelText('Account'), 'acct_9931')
    await user.type(await screen.findByLabelText('Capability'), 'reports.monthly')
    await user.click(await screen.findByRole('button', { name: 'Check' }))

    expect(await screen.findByText(/has been retired since/)).toBeInTheDocument()
  })

  it('says plainly which of the three refusals it was', async () => {
    const user = userEvent.setup()
    server.use(http.get('/admin/v1/check', () =>
      HttpResponse.json({ type: 'entitlement/beyond-history', title: 'Beyond the recorded history', status: 422,
        detail: 'The change history does not reach back to 2019-01-01.' }, { status: 422 })))
    renderWithProviders(<CheckerRoute />)

    await user.type(await screen.findByLabelText('Account'), 'acct_9931')
    await user.type(await screen.findByLabelText('Capability'), 'reports.monthly')
    await user.click(await screen.findByRole('button', { name: 'Check' }))

    expect(await screen.findByText('The change history does not reach back that far.')).toBeInTheDocument()
  })
})

describe('002 — the history on screen 5', () => {
  it('filters by capability (c31)', async () => {
    const user = userEvent.setup()
    const asked: (string | null)[] = []
    server.use(http.get('/admin/v1/audit', ({ request }) => {
      asked.push(new URL(request.url).searchParams.get('capability'))
      return HttpResponse.json({ events: [], nextCursor: null })
    }))
    renderWithProviders(<HistoryRoute />)

    await user.type(await screen.findByLabelText('Capability'), 'reports.monthly')
    await waitFor(() => expect(asked).toContain('reports.monthly'))
  })

  it('reads a beginning as the passage of time, not as an act by someone called clock (c30)', async () => {
    server.use(http.get('/admin/v1/audit', () => HttpResponse.json({
      events: [{
        seq: 91, occurredAt: '2027-10-01T04:00:00.000Z', actor: { id: 'clock', kind: 'SYSTEM' },
        source: 'CLOCK', entityType: 'OVERRIDE', entityId: 'ovr_77', action: 'BEGIN',
        capability: 'reports.monthly', planKey: null, account: 'acct_9931',
        before: null, after: null, reason: 'Began on 2027-10-01, as dated when it was created.',
        affectedAccountCount: null,
      }],
      nextCursor: null,
    })))
    renderWithProviders(<HistoryRoute />)

    const row = (await screen.findByText(/came into force on its start date/)).closest('tr')!
    expect(row).toHaveTextContent('the passage of time')
    expect(row).not.toHaveTextContent('clock (system)')
  })
})
