import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ValueEditor, ValueBadge } from './ValueEditor'
import type { CapabilityTier } from '../types/domain'

const TIERS: CapabilityTier[] = [
  { tier: 'community', ordinal: 0, displayName: 'Community' },
  { tier: 'gold', ordinal: 1, displayName: 'Gold' },
]

describe('ValueEditor', () => {
  it('toggles a SWITCH value', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ValueEditor valueType="SWITCH" tiers={[]} value={{ type: 'SWITCH', enabled: false }} onChange={onChange} />)
    await user.click(screen.getByRole('checkbox', { name: 'Enabled' }))
    expect(onChange).toHaveBeenCalledWith({ type: 'SWITCH', enabled: true })
  })

  it('edits a bounded QUANTITY amount', () => {
    const onChange = vi.fn()
    render(<ValueEditor valueType="QUANTITY" tiers={[]} value={{ type: 'QUANTITY', amount: 50 }} onChange={onChange} />)
    const input = screen.getByRole('spinbutton', { name: 'Amount' })
    fireEvent.change(input, { target: { value: '75' } })
    expect(onChange).toHaveBeenCalledWith({ type: 'QUANTITY', amount: 75 })
  })

  it('switches a QUANTITY to unlimited via the checkbox, disabling the amount field', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ValueEditor valueType="QUANTITY" tiers={[]} value={{ type: 'QUANTITY', amount: 50 }} onChange={onChange} />)
    await user.click(screen.getByRole('checkbox', { name: 'Unlimited' }))
    expect(onChange).toHaveBeenCalledWith({ type: 'QUANTITY', unlimited: true })
  })

  it('picks a TIER from the declared list', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ValueEditor valueType="TIER" tiers={TIERS} value={{ type: 'TIER', tier: 'community' }} onChange={onChange} />)
    await user.selectOptions(screen.getByRole('combobox', { name: 'Tier' }), 'gold')
    expect(onChange).toHaveBeenCalledWith({ type: 'TIER', tier: 'gold' })
  })
})

describe('ValueBadge', () => {
  it('renders unlimited as a word, not a number', () => {
    render(<ValueBadge value={{ type: 'QUANTITY', unlimited: true }} tiers={[]} />)
    expect(screen.getByText('Unlimited')).toBeInTheDocument()
  })

  it('renders a tier by its declared display name', () => {
    render(<ValueBadge value={{ type: 'TIER', tier: 'gold' }} tiers={TIERS} />)
    expect(screen.getByText('Gold')).toBeInTheDocument()
  })
})
