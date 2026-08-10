import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ErrorNotice } from './ErrorNotice'
import { ApiError } from '../api/http'

/**
 * Before this component existed the app had 18 mutations and rendered a failure for exactly one of
 * them: a rejected write left the screen completely unchanged, so "it didn't save" and "it saved"
 * looked identical to the operator. Callers branch on `type`, never on message text (contracts
 * README), so this renders the server's own `detail`/`title` rather than composing its own wording.
 */
describe('ErrorNotice', () => {
  test('renders nothing when there is no error', () => {
    const { container } = render(<ErrorNotice error={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  test('announces the problem detail from an RFC 9457 response', () => {
    const error = new ApiError({
      type: 'entitlement/account-exists',
      title: 'Account already exists',
      status: 422,
      detail: 'An account with external id acct_1177 already exists.',
    })

    render(<ErrorNotice error={error} />)

    expect(screen.getByRole('alert')).toHaveTextContent('An account with external id acct_1177 already exists.')
  })

  test('falls back to the problem title when the response carries no detail', () => {
    const error = new ApiError({ type: 'entitlement/conflict', title: 'Tier is not above the current maximum', status: 409 })

    render(<ErrorNotice error={error} />)

    expect(screen.getByRole('alert')).toHaveTextContent('Tier is not above the current maximum')
  })

  test('renders a plain Error message, so a transport failure is still visible', () => {
    render(<ErrorNotice error={new Error('Failed to fetch')} />)

    expect(screen.getByRole('alert')).toHaveTextContent('Failed to fetch')
  })

  test('prefixes with a caller-supplied action so the operator knows what failed', () => {
    const error = new ApiError({ type: 'entitlement/conflict', title: 'Already retired', status: 409 })

    render(<ErrorNotice error={error} action="Could not retire this capability" />)

    expect(screen.getByRole('alert')).toHaveTextContent('Could not retire this capability')
    expect(screen.getByRole('alert')).toHaveTextContent('Already retired')
  })
})
