import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { PlanEditorRoute } from './PlanEditorRoute'

describe('PlanEditorRoute', () => {
  it('shows an explicit plan value distinctly from a capability falling back to default', async () => {
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await waitFor(() => expect(screen.getByText('50')).toBeInTheDocument())
    expect(screen.getAllByText(/not set — falls back to default \(Off\)/).length).toBeGreaterThan(0)
  })

  it('lets an operator set a capability the plan does not currently mention', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PlanEditorRoute planKey="pro" />)
    await waitFor(() => expect(screen.getAllByText(/not set — falls back to default \(Off\)/).length).toBeGreaterThan(0))
    await user.click(screen.getAllByRole('button', { name: 'Edit' })[0])
    await user.click(screen.getByRole('checkbox', { name: 'Enabled' }))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.getByText('On')).toBeInTheDocument()
  })
})
