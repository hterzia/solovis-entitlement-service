import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
import { makeCapabilities } from '../../test/mocks/fixtures'
import { db } from '../../test/mocks/handlers'
import { CapabilityDetailRoute } from './CapabilityDetailRoute'
import { getCapability } from '../../api/capabilities'

function rejectWith(status: number, detail: string) {
  return HttpResponse.json({ type: 'entitlement/validation-failed', title: 'Validation failed', status, detail }, { status })
}

describe('CapabilityDetailRoute', () => {
  it('shows the value type as read-only with an explanatory title', async () => {
    renderWithProviders(<CapabilityDetailRoute capabilityKey="support" />)
    await waitFor(() => expect(screen.getByText('Support level')).toBeInTheDocument())
    const valueTypeField = screen.getByTestId('value-type-readonly')
    expect(valueTypeField).toHaveTextContent('TIER')
    expect(valueTypeField).toHaveAttribute('title', "A capability has one value type across every plan.")
  })

  it('edits the display name and description', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="api.access" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toHaveValue('API access'))
    await user.clear(screen.getByLabelText('Display name'))
    await user.type(screen.getByLabelText('Display name'), 'API access (v2)')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    await expect(getCapability('api.access')).resolves.toMatchObject({ displayName: 'API access (v2)' })
  })

  it('appends a tier above the current maximum ordinal, with no reordering control offered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="support" />)
    const tierList = await waitFor(() => screen.getByRole('list'))
    expect(within(tierList).getByText('Gold')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reorder/i })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('New tier key'), 'platinum')
    await user.type(screen.getByLabelText('New tier display name'), 'Platinum')
    await user.click(screen.getByRole('button', { name: 'Append tier' }))
    await waitFor(() => expect(within(tierList).getByText('Platinum')).toBeInTheDocument())
    await expect(getCapability('support')).resolves.toMatchObject({
      tiers: [
        { tier: 'community', ordinal: 0 }, { tier: 'standard', ordinal: 1 },
        { tier: 'gold', ordinal: 2 }, { tier: 'platinum', ordinal: 3 },
      ],
    })
  })

  it('does not retire on the initial click — Cancel truly cancels', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="reports.monthly" />)
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Retire capability' }))
    expect(screen.getByText(/used by 1 plan/i)).toBeInTheDocument()
    await expect(getCapability('reports.monthly')).resolves.toMatchObject({ status: 'ACTIVE' })
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByText(/used by 1 plan/i)).not.toBeInTheDocument()
    await expect(getCapability('reports.monthly')).resolves.toMatchObject({ status: 'ACTIVE' })
  })

  it('retires with a confirmation naming usage, and offers no delete control', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="reports.monthly" />)
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retire capability' }))
    expect(screen.getByText(/used by 1 plan/i)).toBeInTheDocument()
    expect(screen.getByText(/permanent/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm retirement' }))
    await waitFor(() => expect(getCapability('reports.monthly')).resolves.toMatchObject({ status: 'RETIRED' }))
    expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument()
  })

  it('drops the save confirmation as soon as a further, unsaved edit is made', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="api.access" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toHaveValue('API access'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Description'), ' and more')
    expect(screen.queryByText('Saved. Active everywhere within 60 seconds.')).not.toBeInTheDocument()
  })

  // A rejected write that leaves the screen unchanged reads as success. Each of the three
  // mutations on this screen has to say so in the operator's face.
  it('reports a rejected save', async () => {
    const user = userEvent.setup()
    server.use(http.patch('/admin/v1/capabilities/:key', () => rejectWith(422, 'Display name must not be blank.')))
    renderWithProviders(<CapabilityDetailRoute capabilityKey="api.access" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toHaveValue('API access'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Display name must not be blank.')
    expect(screen.queryByText('Saved. Active everywhere within 60 seconds.')).not.toBeInTheDocument()
  })

  it('reports a rejected tier append — the 409 for a tier that is not above the maximum', async () => {
    const user = userEvent.setup()
    server.use(http.post('/admin/v1/capabilities/:key/tiers', () => rejectWith(409, "Tier 'bronze' is not above the current maximum.")))
    renderWithProviders(<CapabilityDetailRoute capabilityKey="support" />)
    await waitFor(() => expect(screen.getByLabelText('New tier key')).toBeInTheDocument())
    await user.type(screen.getByLabelText('New tier key'), 'bronze')
    await user.type(screen.getByLabelText('New tier display name'), 'Bronze')
    await user.click(screen.getByRole('button', { name: 'Append tier' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Tier 'bronze' is not above the current maximum.")
  })

  it('reports a rejected retirement', async () => {
    const user = userEvent.setup()
    server.use(http.post('/admin/v1/capabilities/:key/retire', () => rejectWith(409, 'Already retired.')))
    renderWithProviders(<CapabilityDetailRoute capabilityKey="reports.monthly" />)
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Retire capability' }))
    await user.click(screen.getByRole('button', { name: 'Confirm retirement' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Already retired.')
    expect(screen.queryByText('Saved. Active everywhere within 60 seconds.')).not.toBeInTheDocument()
  })

  it('reports a capability that cannot be loaded rather than spinning for ever', async () => {
    renderWithProviders(<CapabilityDetailRoute capabilityKey="no.such.capability" />)
    expect(await screen.findByRole('alert')).toHaveTextContent("No capability 'no.such.capability'.")
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })

  // §5: the off-value rules are made visible here exactly as CapabilityCreateForm makes them
  // visible at declaration time — hidden for SWITCH, pinned to 0 for QUANTITY, a declared tier
  // or none for TIER.
  it('offers no off-value control for a SWITCH capability, per §5', async () => {
    renderWithProviders(<CapabilityDetailRoute capabilityKey="api.access" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toHaveValue('API access'))
    expect(screen.queryByLabelText(/off/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/off-value/i)).not.toBeInTheDocument()
  })

  it('constrains a QUANTITY off-value to 0, offered as a checkbox not a number field', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="reports.monthly" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toHaveValue('Monthly reports'))
    const offAtZero = screen.getByRole('checkbox', { name: /off at 0/i })
    expect(offAtZero).not.toBeChecked()
    expect(screen.queryByRole('spinbutton', { name: /off-value/i })).not.toBeInTheDocument()
    await user.click(offAtZero)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    await expect(getCapability('reports.monthly')).resolves.toMatchObject({ offValue: { type: 'QUANTITY', amount: 0 } })
  })

  it('shows an off-value that is already declared, so it can be seen and corrected', async () => {
    db.capabilities.find((c) => c.key === 'reports.monthly')!.offValue = { type: 'QUANTITY', amount: 0 }
    renderWithProviders(<CapabilityDetailRoute capabilityKey="reports.monthly" />)
    await waitFor(() => expect(screen.getByRole('checkbox', { name: /off at 0/i })).toBeChecked())
  })

  it('picks a TIER off-value from the declared tiers, or none', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityDetailRoute capabilityKey="support" />)
    const select = await screen.findByRole('combobox', { name: 'Off-value tier' })
    expect(select).toHaveValue('')
    expect(within(select).getByRole('option', { name: 'None' })).toBeInTheDocument()
    expect(within(select).getByRole('option', { name: 'Gold' })).toBeInTheDocument()
    await user.selectOptions(select, 'community')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    await expect(getCapability('support')).resolves.toMatchObject({ offValue: { type: 'TIER', tier: 'community' } })
  })

  // Every edit control clears the previous save's outcome, so a stale "Saved." cannot sit above a
  // form the operator has since changed. Clearing a *pending* save is a different act: it detaches
  // the observer, and the result — success or failure — is never rendered. The write still lands.
  it('still reports a failure when the operator keeps typing while the save is in flight', async () => {
    const user = userEvent.setup()
    server.use(
      http.patch('/admin/v1/capabilities/:key', async () => {
        await delay(50)
        return HttpResponse.json(
          { type: 'entitlement/internal', title: 'Boom', status: 500, detail: 'Injected failure' },
          { status: 500 },
        )
      }),
    )
    renderWithProviders(<CapabilityDetailRoute capabilityKey="seats" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await user.type(screen.getByLabelText('Display name'), 'X')

    expect(await screen.findByRole('alert')).toHaveTextContent('Injected failure')
  })

  it('still confirms a save that succeeds while the operator keeps typing', async () => {
    const user = userEvent.setup()
    server.use(
      http.patch('/admin/v1/capabilities/:key', async () => {
        await delay(50)
        return HttpResponse.json({ ...makeCapabilities().find((c) => c.key === 'seats')! })
      }),
    )
    renderWithProviders(<CapabilityDetailRoute capabilityKey="seats" />)
    await waitFor(() => expect(screen.getByLabelText('Display name')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await user.type(screen.getByLabelText('Display name'), 'X')

    expect(await screen.findByRole('status')).toHaveTextContent('Saved.')
  })
})
