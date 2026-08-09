import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { CapabilitiesListRoute } from './CapabilitiesListRoute'
import { db } from '../../test/mocks/handlers'

describe('CapabilitiesListRoute', () => {
  it('lists active capabilities grouped by area', async () => {
    renderWithProviders(<CapabilitiesListRoute />)
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
    expect(screen.queryByText('reports.monthly retired')).not.toBeInTheDocument()
  })

  it('hides retired capabilities until "show retired" is checked', async () => {
    db.capabilities[0].status = 'RETIRED'
    const user = userEvent.setup()
    renderWithProviders(<CapabilitiesListRoute />)
    await waitFor(() => expect(screen.queryByText('API access')).toBeInTheDocument())
    expect(screen.queryByText('Monthly reports')).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Show retired' }))
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
  })
})
