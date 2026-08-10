import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
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

  // contracts/ui-screens.md, Screen 1: "Each row: key, display name, value type, default,
  // off-value, status."
  it('renders key, display name, value type, default, off-value and status on every row', async () => {
    db.capabilities.find((c) => c.key === 'reports.monthly')!.offValue = { type: 'QUANTITY', amount: 0 }
    renderWithProviders(<CapabilitiesListRoute />)
    const row = await screen.findByTestId('capability-row-reports.monthly')
    expect(within(row).getByText('reports.monthly')).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: 'Monthly reports' })).toBeInTheDocument()
    expect(within(row).getByTestId('capability-value-type')).toHaveTextContent('QUANTITY')
    expect(within(row).getByTestId('capability-default')).toHaveTextContent('0')
    expect(within(row).getByTestId('capability-off-value')).toHaveTextContent('0')
    expect(within(row).getByTestId('capability-status')).toHaveTextContent('Active')
  })

  it('says so when a capability declares no off-value', async () => {
    renderWithProviders(<CapabilitiesListRoute />)
    const row = await screen.findByTestId('capability-row-api.access')
    expect(within(row).getByTestId('capability-off-value')).toHaveTextContent('None')
    expect(within(row).getByTestId('capability-default')).toHaveTextContent('Off')
  })

  it('shows retired capabilities dimmed rather than letting them vanish', async () => {
    db.capabilities.find((c) => c.key === 'reports.monthly')!.status = 'RETIRED'
    const user = userEvent.setup()
    renderWithProviders(<CapabilitiesListRoute />)
    await waitFor(() => expect(screen.getByText('API access')).toBeInTheDocument())
    await user.click(screen.getByRole('checkbox', { name: 'Show retired' }))
    const row = await screen.findByTestId('capability-row-reports.monthly')
    expect(within(row).getByTestId('capability-status')).toHaveTextContent('Retired')
    expect(row).toHaveAttribute('data-retired', 'true')
    expect(screen.getByTestId('capability-row-api.access')).not.toHaveAttribute('data-retired', 'true')
  })

  // c40: the area grouping, collapse and search are load-bearing at several hundred rows, and the
  // denser row must not have cost any of them.
  it('keeps the area grouping, collapse and search working over the denser rows', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CapabilitiesListRoute />)
    await waitFor(() => expect(screen.getByText('Monthly reports')).toBeInTheDocument())
    const reportsGroup = screen.getByTestId('capability-group-reports')
    await user.click(within(reportsGroup).getByRole('button', { expanded: true }))
    expect(screen.queryByTestId('capability-row-reports.monthly')).not.toBeInTheDocument()
    await user.click(within(reportsGroup).getByRole('button', { expanded: false }))
    await user.type(screen.getByLabelText('Search capabilities'), 'api.')
    await waitFor(() => expect(screen.queryByTestId('capability-row-reports.monthly')).not.toBeInTheDocument())
    expect(screen.getByTestId('capability-row-api.access')).toBeInTheDocument()
  })

  // An unreachable catalogue and an empty one are different facts. Rendering `{query.data && …}`
  // with no error branch made them identical: the screen simply showed its header and no rows.
  it('reports a failed load rather than rendering as though the capabilities were empty', async () => {
    server.use(http.get('/admin/v1/capabilities', () => HttpResponse.error()))
    renderWithProviders(<CapabilitiesListRoute />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
