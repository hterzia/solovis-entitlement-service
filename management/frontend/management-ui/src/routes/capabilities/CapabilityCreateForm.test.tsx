import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CapabilityCreateForm } from './CapabilityCreateForm'
import { getCapability } from '../../api/capabilities'

describe('CapabilityCreateForm', () => {
  it('hides the off-value control for SWITCH, per §5', async () => {
    renderWithProviders(<CapabilityCreateForm onCreated={vi.fn()} />)
    // RouterProvider's initial route match is async (Task 10, commit 94ad1d3) — wait for the
    // form to actually mount before asserting absence, so this doesn't pass vacuously pre-mount.
    await screen.findByLabelText('Key')
    expect(screen.queryByLabelText(/off-value/i)).not.toBeInTheDocument()
  })

  it('constrains the off-value to 0 for QUANTITY, offered as a checkbox not a number field', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityCreateForm onCreated={vi.fn()} />)
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Value type' }), 'QUANTITY')
    expect(screen.getByRole('checkbox', { name: /off at 0/i })).toBeInTheDocument()
    expect(screen.queryByRole('spinbutton', { name: /off-value/i })).not.toBeInTheDocument()
  })

  it('requires at least two tiers before submit is enabled for a TIER capability', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityCreateForm onCreated={vi.fn()} />)
    await user.type(await screen.findByLabelText('Key'), 'sla.level')
    await user.type(screen.getByLabelText('Display name'), 'SLA level')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Value type' }), 'TIER')
    expect(screen.getByRole('button', { name: 'Declare capability' })).toBeDisabled()
    await user.type(screen.getAllByLabelText('Tier key')[0], 'none')
    await user.type(screen.getAllByLabelText('Tier display name')[0], 'None')
    expect(screen.getByRole('button', { name: 'Declare capability' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Add tier' }))
    await user.type(screen.getAllByLabelText('Tier key')[1], 'standard')
    await user.type(screen.getAllByLabelText('Tier display name')[1], 'Standard')
    expect(screen.getByRole('button', { name: 'Declare capability' })).toBeEnabled()
  })

  it('creates a SWITCH capability and reports the liveness promise', async () => {
    const user = userEvent.setup()
    const onCreated = vi.fn()
    renderWithProviders(<CapabilityCreateForm onCreated={onCreated} />)
    await user.type(await screen.findByLabelText('Key'), 'integration.hubspot')
    await user.type(screen.getByLabelText('Display name'), 'HubSpot integration')
    await user.click(screen.getByRole('button', { name: 'Declare capability' }))
    await waitFor(() => expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument())
    expect(onCreated).toHaveBeenCalled()
    await expect(getCapability('integration.hubspot')).resolves.toMatchObject({ valueType: 'SWITCH' })
  })

  it('reports a rejected declaration through the shared error notice', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilityCreateForm onCreated={vi.fn()} />)
    await user.type(await screen.findByLabelText('Key'), 'reports.monthly')
    await user.type(screen.getByLabelText('Display name'), 'Monthly reports')
    await user.click(screen.getByRole('button', { name: 'Declare capability' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Key 'reports.monthly' already declared.")
  })
})
