import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/testUtils'
import { SaveConfirmation } from './SaveConfirmation'

describe('SaveConfirmation', () => {
  it('states the exact liveness promise with the given second count', async () => {
    renderWithProviders(<SaveConfirmation seconds={60} />)
    expect(await screen.findByText('Saved. Active everywhere within 60 seconds.')).toBeInTheDocument()
  })

  // Five mutations take the number from `GET /admin/v1/meta`, a query unrelated to the write. When
  // that query fails the number is unknowable — but the write still landed, and saying nothing at
  // all is the worst available answer: the operator cannot tell a completed save from a lost one
  // and will try again. Confirm the save; add the promise only when it can honestly be stated.
  it('still confirms the save when the propagation window is unknown', async () => {
    renderWithProviders(<SaveConfirmation seconds={undefined} />)

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('Saved.')
    expect(status).not.toHaveTextContent('Active everywhere within')
  })

  it('never invents a number when none was supplied', async () => {
    renderWithProviders(<SaveConfirmation seconds={undefined} />)
    expect(screen.queryByText(/\d+ seconds/)).not.toBeInTheDocument()
  })
})
