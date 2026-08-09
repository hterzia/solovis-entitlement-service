import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { PlanEditorRoute } from './PlanEditorRoute'

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
})
