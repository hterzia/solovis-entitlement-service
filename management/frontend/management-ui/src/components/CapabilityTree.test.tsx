import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { render } from '@testing-library/react'
import { CapabilityTree } from './CapabilityTree'

interface Row { key: string; area: string; displayName: string }

const ROWS: Row[] = [
  { key: 'reports.monthly', area: 'reports', displayName: 'Monthly reports' },
  { key: 'api.access', area: 'api', displayName: 'API access' },
  { key: 'residency.eu', area: 'residency', displayName: 'EU residency' },
  { key: 'residency.us', area: 'residency', displayName: 'US residency' },
]

describe('CapabilityTree', () => {
  it('groups rows under their area heading', () => {
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} />)
    expect(screen.getByRole('heading', { name: /residency/i })).toBeInTheDocument()
    const residencyGroup = screen.getByTestId('capability-group-residency')
    expect(within(residencyGroup).getByText('EU residency')).toBeInTheDocument()
    expect(within(residencyGroup).getByText('US residency')).toBeInTheDocument()
  })

  it('collapses and re-expands a group on click', async () => {
    const user = userEvent.setup()
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} />)
    await user.click(screen.getByRole('button', { name: /residency/i }))
    expect(screen.queryByText('EU residency')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /residency/i }))
    expect(screen.getByText('EU residency')).toBeInTheDocument()
  })

  it('filters rows by search text across key and display name', async () => {
    const user = userEvent.setup()
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} />)
    await user.type(screen.getByPlaceholderText('Search capabilities'), 'monthly')
    expect(screen.getByText('Monthly reports')).toBeInTheDocument()
    expect(screen.queryByText('API access')).not.toBeInTheDocument()
  })

  it('renders the empty message when no rows match', async () => {
    const user = userEvent.setup()
    render(<CapabilityTree items={ROWS} renderRow={(r) => <span>{r.displayName}</span>} emptyMessage="No capabilities found." />)
    await user.type(screen.getByPlaceholderText('Search capabilities'), 'zzz-no-match')
    expect(screen.getByText('No capabilities found.')).toBeInTheDocument()
  })
})
