import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/testUtils'
import { SaveConfirmation } from './SaveConfirmation'

describe('SaveConfirmation', () => {
  it('states the exact liveness promise with the given second count', () => {
    renderWithProviders(<SaveConfirmation seconds={60} />)
    expect(screen.getByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument()
  })
})
