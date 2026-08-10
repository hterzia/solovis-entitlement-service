import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
import { PlanEditorRoute } from './PlanEditorRoute'

function problem(status: number, type: string, detail: string) {
  return HttpResponse.json({ type, title: type, status, detail }, { status })
}

async function makeAChange(user: ReturnType<typeof userEvent.setup>) {
  // getAllByText, not getByText: two ACTIVE capabilities (api.access, export.parquet) share the
  // same SWITCH default (Off) and neither has an explicit "pro" plan value, so more than one row
  // renders this identical fallback text — the wait only needs to confirm the tree has loaded.
  await waitFor(() => expect(screen.getAllByText(/not set — falls back to default \(Off\)/).length).toBeGreaterThan(0))
  await user.click(screen.getAllByRole('button', { name: 'Edit' })[0])
  await user.click(screen.getByRole('checkbox', { name: 'Enabled' }))
  await user.click(screen.getByRole('button', { name: 'Done' }))
}

describe('PlanEditorRoute', () => {
  it('shows an explicit plan value distinctly from a capability falling back to default', async () => {
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await waitFor(() => expect(screen.getByText('50')).toBeInTheDocument())
    expect(screen.getAllByText(/not set — falls back to default \(Off\)/).length).toBeGreaterThan(0)
  })

  it('lets an operator set a capability the plan does not currently mention', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    expect(screen.getByText('On')).toBeInTheDocument()
  })

  it('disables Save until a preview has been fetched', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('shows a non-dismissable affected-account banner and a diff on review', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText('This change affects 26890 accounts.')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /dismiss/i })).not.toBeInTheDocument()
  })

  it('explains why Save stays disabled when the review carried no preview account', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText('This change affects 26890 accounts.')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByText('Provide a preview account above to enable Save.')).toBeInTheDocument()
  })

  it('previews the change on one named account and calls out "no change"', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.type(screen.getByLabelText('Preview account'), 'acct_9931')
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText(/No change for this account/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('saves once a preview exists and shows the resulting liveness promise', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.type(screen.getByLabelText('Preview account'), 'acct_9931')
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
  })

  it('clears the previous save confirmation when a new edit is made', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.type(screen.getByLabelText('Preview account'), 'acct_9931')
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    await makeAChange(user)
    expect(screen.queryByText('Saved. Active everywhere within 60 seconds.')).not.toBeInTheDocument()
  })

  it('reports a plan that will not load instead of spinning on Loading…', async () => {
    server.use(http.get('/admin/v1/plans/:key', () =>
      problem(404, 'entitlement/unknown-capability', "No plan 'no-such-plan'.")))
    renderWithProviders(<PlanEditorRoute planKey="no-such-plan" />)
    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent("Could not load this plan: No plan 'no-such-plan'."))
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })

  it('names tiers in the diff the way the operator declared them', async () => {
    server.use(http.post('/admin/v1/plans/:key/entitlements/preview', () =>
      HttpResponse.json({
        planKey: 'pro',
        affectedAccountCount: 26890,
        diff: [{ capability: 'support', before: { type: 'TIER', tier: 'standard' }, after: { type: 'TIER', tier: 'gold' } }],
        previewToken: 'pv_test_token',
      })))
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText('support: Standard → Gold')).toBeInTheDocument())
  })

  it('speaks of one affected account in the singular', async () => {
    server.use(http.post('/admin/v1/plans/:key/entitlements/preview', () =>
      HttpResponse.json({ planKey: 'pro', affectedAccountCount: 1, diff: [], previewToken: 'pv_test_token' })))
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText('This change affects 1 account.')).toBeInTheDocument())
  })

  it('shows the service\'s own words when the review is refused', async () => {
    server.use(http.post('/admin/v1/plans/:key/entitlements/preview', () =>
      problem(422, 'entitlement/validation-failed', "Capability 'api.access' is retired.")))
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByText(/Could not review this change: Capability 'api.access' is retired\./))
      .toBeInTheDocument())
  })

  it('shows the service\'s own words when the save is refused for a stale preview', async () => {
    server.use(http.put('/admin/v1/plans/:key/entitlements', () =>
      problem(409, 'entitlement/validation-failed', 'Missing or stale preview token.')))
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await makeAChange(user)
    await user.type(screen.getByLabelText('Preview account'), 'acct_9931')
    await user.click(screen.getByRole('button', { name: 'Review changes' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByText(/Could not save this change: Missing or stale preview token\./))
      .toBeInTheDocument())
    expect(screen.queryByText('Saved. Active everywhere within 60 seconds.')).not.toBeInTheDocument()
  })
})
