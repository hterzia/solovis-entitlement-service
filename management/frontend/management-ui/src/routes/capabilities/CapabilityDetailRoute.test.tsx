import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CapabilityDetailRoute } from './CapabilityDetailRoute'
import { getCapability } from '../../api/capabilities'

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
  })
})
