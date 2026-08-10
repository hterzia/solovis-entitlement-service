import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { server } from '../../test/mocks/server'
import { makeAccount, makeCapabilities } from '../../test/mocks/fixtures'
import { renderWithProviders } from '../../test/testUtils'
import { AccountDetailRoute } from './AccountDetailRoute'

// A TIER-valued trace, which the shared QUANTITY fixture cannot be: the only way to see whether a
// result panel was given the capability's declared tiers is whether it prints 'Gold' or 'gold'.
const TIER_TRACE = {
  baseline: { source: 'CAPABILITY_DEFAULT', value: { type: 'TIER', tier: 'community' }, note: 'Capability default.' },
  grants: [
    {
      overrideId: 'ovr_tier', value: { type: 'TIER', tier: 'gold' }, reason: 'Support upgrade',
      createdBy: 'dev-operator', createdAt: '2026-08-10T00:00:00.000Z', outcome: 'WON',
    },
  ],
  grantStep: { applied: true, winner: 'ovr_tier', value: { type: 'TIER', tier: 'gold' }, note: 'Most generous GRANT wins.' },
  holds: [],
  holdStep: { applied: false, why: 'NO_HOLDS' },
  result: { value: { type: 'TIER', tier: 'gold' }, allowed: true, allowedReason: 'NO_OFF_VALUE_DECLARED' },
}

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
    expect(screen.getByText(/Active everywhere within 60 seconds/)).toBeInTheDocument()
  })

  it('states how many overrides survive the plan change before the operator confirms it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change plan' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    expect(screen.getByText(/2 overrides on this account will be kept/)).toBeInTheDocument()
  })

  it('promises the change is live everywhere after an override is added', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add override' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    await user.click(screen.getByRole('button', { name: 'Save override' }))
    await waitFor(() => expect(screen.getByText(/Active everywhere within 60 seconds/)).toBeInTheDocument())
  })

  it('promises the change is live everywhere after an override is removed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2))
    await user.click(screen.getByTestId('remove-ovr_7788'))
    await user.click(screen.getByRole('button', { name: 'Confirm removal' }))
    await waitFor(() => expect(screen.getByText(/Active everywhere within 60 seconds/)).toBeInTheDocument())
  })

  it('clears a previous action’s result when a different action is started', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add override' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    await user.click(screen.getByRole('button', { name: 'Save override' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Resulting decision' })).toBeInTheDocument())

    await user.click(screen.getByTestId('remove-ovr_7788'))
    expect(screen.queryByRole('heading', { name: 'Resulting decision' })).not.toBeInTheDocument()
  })

  it('shows the service’s own words when adding an override is rejected', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('/admin/v1/accounts/:external/overrides', () =>
        HttpResponse.json(
          { type: 'entitlement/validation-failed', title: 'Validation failed', status: 422, detail: 'A GRANT may not lower the plan value.' },
          { status: 422 },
        ),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add override' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    await user.click(screen.getByRole('button', { name: 'Save override' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('A GRANT may not lower the plan value.'))
  })

  it('shows the service’s own words when removing an override is rejected', async () => {
    const user = userEvent.setup()
    server.use(
      http.delete('/admin/v1/accounts/:external/overrides/:id', () =>
        HttpResponse.json(
          { type: 'entitlement/reason-required', title: 'Reason required', status: 422, detail: 'A removal must carry a reason.' },
          { status: 422 },
        ),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2))
    await user.click(screen.getByTestId('remove-ovr_7788'))
    await user.click(screen.getByRole('button', { name: 'Confirm removal' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('A removal must carry a reason.'))
  })

  it('shows the service’s own words when the plan change is rejected', async () => {
    const user = userEvent.setup()
    server.use(
      http.put('/admin/v1/accounts/:external/plan', () =>
        HttpResponse.json(
          { type: 'entitlement/validation-failed', title: 'Validation failed', status: 422, detail: 'Plan ‘free’ is archived.' },
          { status: 422 },
        ),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change plan' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    await user.selectOptions(screen.getByLabelText('New plan'), 'free')
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Plan ‘free’ is archived.'))
  })

  it('reports an account that cannot be loaded instead of spinning on “Loading…” forever', async () => {
    renderWithProviders(<AccountDetailRoute external="no-such-account" />)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent("No account 'no-such-account'."))
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })

  it('reports a trace that cannot be loaded', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/admin/v1/check', () =>
        HttpResponse.json(
          { type: 'entitlement/retired-capability', title: 'Retired capability', status: 409, detail: 'That capability is retired and is no longer evaluated.' },
          { status: 409 },
        ),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Why?' })[0])
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('That capability is retired and is no longer evaluated.'),
    )
  })

  it('groups the effective entitlements by area', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('reports.monthly')).toBeInTheDocument())
    const reports = screen.getByTestId('entitlement-area-reports')
    expect(within(reports).getByText('reports')).toBeInTheDocument()
    expect(within(reports).getByTestId('entitlement-reports.monthly')).toBeInTheDocument()
    expect(within(reports).queryByTestId('entitlement-seats')).not.toBeInTheDocument()
    expect(within(screen.getByTestId('entitlement-area-seats')).getByTestId('entitlement-seats')).toBeInTheDocument()
    expect(within(screen.getByTestId('entitlement-area-export')).getByTestId('entitlement-export.parquet')).toBeInTheDocument()
  })

  it('names the capability each override applies to', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText(/Renewal concession/)).toBeInTheDocument())
    expect(screen.getByTestId('override-ovr_4471')).toHaveTextContent('reports.monthly')
    expect(screen.getByTestId('override-ovr_7788')).toHaveTextContent('reports.monthly')
  })

  it('shows the account identifier even when the account carries a name', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Northwind Capital' })).toBeInTheDocument())
    expect(screen.getByText('acct_9931')).toBeInTheDocument()
  })

  it('labels the source of each effective entitlement the way the contract words it', async () => {
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByText('export.parquet')).toBeInTheDocument())
    expect(screen.getByTestId('entitlement-export.parquet')).toHaveTextContent('default')
    expect(screen.getByTestId('entitlement-seats')).toHaveTextContent('plan')
    expect(screen.getByTestId('entitlement-reports.monthly')).toHaveTextContent('HOLD')
  })

  it('names the declared tier in the trace shown after an override is saved', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('/admin/v1/accounts/:external/overrides', () =>
        HttpResponse.json(
          {
            overrideId: 'ovr_tier',
            decision: { allowed: true, value: { type: 'TIER', tier: 'gold' }, trace: TIER_TRACE },
            snapshotVersion: 48212,
            changeVisibleEverywhereWithinSeconds: 60,
          },
          { status: 201 },
        ),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add override' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'support')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    await user.type(screen.getByLabelText('Reason'), 'Support upgrade')
    await user.click(screen.getByRole('button', { name: 'Save override' }))
    const panel = await screen.findByTestId('added-decision')
    expect(within(panel).getAllByText('Gold').length).toBeGreaterThan(0)
    expect(within(panel).queryByText('gold')).not.toBeInTheDocument()
  })

  it('names the declared tier in the trace shown after an override is removed', async () => {
    const user = userEvent.setup()
    const account = makeAccount()
    account.overrides = [
      {
        id: 'ovr_tier', capability: 'support', kind: 'GRANT', value: { type: 'TIER', tier: 'gold' },
        reason: 'Support upgrade', createdBy: 'dev-operator', createdAt: '2026-08-10T00:00:00.000Z', effectNow: 'WINNING',
      },
    ]
    server.use(
      http.get('/admin/v1/accounts/:external', () => HttpResponse.json(account)),
      http.delete('/admin/v1/accounts/:external/overrides/:id', () =>
        HttpResponse.json({
          overrideId: 'ovr_tier',
          decision: { allowed: true, value: { type: 'TIER', tier: 'gold' }, trace: TIER_TRACE },
          snapshotVersion: 48212,
          changeVisibleEverywhereWithinSeconds: 60,
        }),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByTestId('remove-ovr_tier')).toBeInTheDocument())
    await user.click(screen.getByTestId('remove-ovr_tier'))
    await user.click(screen.getByRole('button', { name: 'Confirm removal' }))
    const panel = await screen.findByTestId('removed-decision')
    expect(within(panel).getAllByText('Gold').length).toBeGreaterThan(0)
    expect(within(panel).queryByText('gold')).not.toBeInTheDocument()
  })

  it('speaks of a single override in the singular', async () => {
    const user = userEvent.setup()
    const account = makeAccount()
    account.overrides = [account.overrides[0]]
    server.use(
      http.get('/admin/v1/accounts/:external', () => HttpResponse.json(account)),
      http.put('/admin/v1/accounts/:external/plan', () =>
        HttpResponse.json({ account: 'acct_9931', planKey: 'free', retainedOverrideCount: 1, snapshotVersion: 48212 }),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change plan' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    expect(screen.getByText(/1 override on this account will be kept/)).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('New plan'), 'free')
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    await waitFor(() => expect(screen.getByText(/1 override is retained/)).toBeInTheDocument())
  })

  it('speaks of no overrides in the plural', async () => {
    const user = userEvent.setup()
    const account = makeAccount()
    account.overrides = []
    server.use(http.get('/admin/v1/accounts/:external', () => HttpResponse.json(account)))
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change plan' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    expect(screen.getByText(/0 overrides on this account will be kept/)).toBeInTheDocument()
  })

  it('empties the add-override form after a successful save, so a second click cannot duplicate it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add override' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    await waitFor(() => expect(screen.getByLabelText('Capability')).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.selectOptions(screen.getByLabelText('Kind'), 'GRANT')
    await user.type(screen.getByLabelText('Reason'), 'Pilot expansion')
    await user.click(screen.getByRole('button', { name: 'Save override' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Resulting decision' })).toBeInTheDocument())
    expect(screen.getByLabelText('Capability')).toHaveValue('')
    expect(screen.getByLabelText('Kind')).toHaveValue('')
    expect(screen.getByLabelText('Reason')).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Save override' })).toBeDisabled()
  })

  it('clears a previous action’s failure when a different action is started', async () => {
    const user = userEvent.setup()
    server.use(
      http.put('/admin/v1/accounts/:external/plan', () =>
        HttpResponse.json(
          { type: 'entitlement/validation-failed', title: 'Validation failed', status: 422, detail: 'Plan ‘free’ is archived.' },
          { status: 422 },
        ),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change plan' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    await user.selectOptions(screen.getByLabelText('New plan'), 'free')
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Add override' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  // c14/c15. The confirmation must state what the value returns to *before* the operator commits,
  // and that answer comes from the service's own resolver — the SPA never re-runs §4's combining
  // rule to work it out. In the fixture, ovr_7788 is a winning HOLD of 0 capping a GRANT of 200,
  // so removing it restores 200; a confirmation that cannot say so is the whole point of the gap.
  it('states the value the account returns to before a removal is confirmed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2))
    await user.click(screen.getByTestId('remove-ovr_7788'))

    const preview = await screen.findByTestId('removal-preview')
    expect(preview).toHaveTextContent('200')
  })

  it('names the declared tier in the removal preview rather than the raw tier key', async () => {
    const user = userEvent.setup()
    const account = makeAccount()
    account.overrides = [
      {
        id: 'ovr_tier', capability: 'support', kind: 'GRANT', value: { type: 'TIER', tier: 'gold' },
        reason: 'Support upgrade', createdBy: 'dev-operator', createdAt: '2026-08-10T00:00:00.000Z',
        effectNow: 'WINNING',
      },
    ]
    server.use(
      http.get('/admin/v1/accounts/:external', () => HttpResponse.json(account)),
      http.get('/admin/v1/accounts/:external/overrides/:id/removal-preview', () =>
        HttpResponse.json({
          account: 'acct_9931', capability: 'support', allowed: true,
          value: { type: 'TIER', tier: 'gold' }, snapshotVersion: 48211,
          evaluatedAt: '2026-08-10T00:00:00.000Z', trace: TIER_TRACE,
        }),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    await waitFor(() => expect(screen.getByTestId('remove-ovr_tier')).toBeInTheDocument())
    await user.click(screen.getByTestId('remove-ovr_tier'))

    const preview = await screen.findByTestId('removal-preview')
    expect(preview).toHaveTextContent('Gold')
    expect(preview).not.toHaveTextContent('gold')
  })

  it('surfaces a failed removal preview instead of silently showing nothing', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/admin/v1/accounts/:external/overrides/:id/removal-preview', () =>
        HttpResponse.json(
          { type: 'entitlement/validation-failed', title: 'Validation failed', status: 422, detail: 'Override ‘ovr_7788’ is already removed.' },
          { status: 422 },
        ),
      ),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2))
    await user.click(screen.getByTestId('remove-ovr_7788'))

    expect(await screen.findByRole('alert')).toHaveTextContent('already removed')
  })

  // An override survives its capability's retirement (c8: capabilities are retired, never deleted,
  // and the overrides keep their referent). The screen loaded only ACTIVE capabilities, so the tier
  // lookup missed and the row printed the raw key; the service also omits `effectNow` for a retired
  // capability, so the effect rendered as an empty string after a dangling em dash.
  it('renders an override on a retired capability with its tier name and a stated effect', async () => {
    const account = makeAccount()
    account.overrides = [
      {
        id: 'ovr_retired', capability: 'support', kind: 'GRANT', value: { type: 'TIER', tier: 'gold' },
        reason: 'Retired-capability probe', createdBy: 'dev-operator',
        createdAt: '2026-08-10T00:00:00.000Z',
      } as unknown as (typeof account)['overrides'][number],
    ]
    server.use(
      http.get('/admin/v1/accounts/:external', () => HttpResponse.json(account)),
      http.get('/admin/v1/capabilities', ({ request }) => {
        const status = new URL(request.url).searchParams.get('status')
        const support = { ...makeCapabilities().find((c) => c.key === 'support')!, status: 'RETIRED' as const }
        // ACTIVE excludes it, exactly as the real service does — the screen must cope.
        return HttpResponse.json({ capabilities: status === 'ALL' ? [support] : [], snapshotVersion: 48211 })
      }),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)

    const row = await screen.findByTestId('override-ovr_retired')
    expect(row).toHaveTextContent('Gold')
    expect(row).not.toHaveTextContent('gold')
    expect(row).toHaveTextContent(/retired/i)
  })

  // Same class as the capability editor's reset race. Collapsing a panel clears that action's
  // result so two contradictory outcomes cannot sit on screen at once — but a request already in
  // flight is not a previous outcome. The write may well land; "I closed the panel" is not
  // "I cancelled the write", and its failure must survive to be seen.
  it('keeps a failure that arrives after its panel was collapsed mid-flight', async () => {
    const user = userEvent.setup()
    server.use(
      http.put('/admin/v1/accounts/:external/plan', async () => {
        await delay(50)
        return HttpResponse.json(
          { type: 'entitlement/validation-failed', title: 'Validation failed', status: 422, detail: 'Injected plan failure.' },
          { status: 422 },
        )
      }),
    )
    renderWithProviders(<AccountDetailRoute external="acct_9931" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change plan' })).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    await user.selectOptions(screen.getByLabelText('New plan'), 'free')
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    await user.click(screen.getByRole('button', { name: 'Change plan' })) // collapse mid-flight

    expect(await screen.findByRole('alert')).toHaveTextContent('Injected plan failure.')
  })
})
