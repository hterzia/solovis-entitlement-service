import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TraceView } from './TraceView'
import { RESULT_TRACE } from '../test/mocks/fixtures'

describe('TraceView', () => {
  it('names the baseline source and the plan that set it', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByText(/Plan 'pro' sets this capability\./)).toBeInTheDocument()
  })

  it('shows every GRANT, winners and losers alike, with reason/author/date', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByText(/Renewal concession.*Q3 pilot/)).toBeInTheDocument()
    expect(screen.getByText(/Migration goodwill/)).toBeInTheDocument()
    expect(screen.getByText(/j\.okafor/)).toBeInTheDocument()
  })

  it('marks the winning grant and the losing grant distinctly, in words rather than enum names', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('grant-ovr_4471')).toHaveTextContent('won')
    expect(screen.getByTestId('grant-ovr_2210')).toHaveTextContent('lost — less generous')
    expect(screen.getByTestId('grant-ovr_2210')).not.toHaveTextContent('LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT')
  })

  it('shows the winning HOLD', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('hold-ovr_7788')).toHaveTextContent('won')
  })

  it('falls back to the raw outcome when it has no label, so nothing silently disappears', () => {
    const unknownOutcome = {
      ...RESULT_TRACE,
      grants: [{ ...RESULT_TRACE.grants[0], outcome: 'SOME_FUTURE_OUTCOME' }],
    }
    render(<TraceView trace={unknownOutcome} />)
    expect(screen.getByTestId('grant-ovr_4471')).toHaveTextContent('SOME_FUTURE_OUTCOME')
  })

  it('renders the result value, allowed flag, and why it is allowed', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('trace-result')).toHaveTextContent('0')
    expect(screen.getByTestId('trace-result')).toHaveTextContent('allowed: true')
    expect(screen.getByTestId('trace-result')).toHaveTextContent('no off-value declared')
    expect(screen.getByTestId('trace-result')).not.toHaveTextContent('NO_OFF_VALUE_DECLARED')
  })

  it('labels each candidate value with the tier names it was given', () => {
    const tiers = [
      { tier: 'basic', ordinal: 0, displayName: 'Basic tier' },
      { tier: 'premium', ordinal: 1, displayName: 'Premium tier' },
    ]
    const tierTrace = {
      ...RESULT_TRACE,
      grants: [{ ...RESULT_TRACE.grants[0], value: { type: 'TIER' as const, tier: 'premium' } }],
    }
    render(<TraceView trace={tierTrace} tiers={tiers} />)
    expect(screen.getByTestId('grant-ovr_4471')).toHaveTextContent('Premium tier')
  })

  it('prints denial by absence as a line, not an empty region, when no GRANTs exist', () => {
    const noGrants = {
      ...RESULT_TRACE,
      grants: [],
      grantStep: { applied: false, why: 'NO_GRANTS' },
    }
    render(<TraceView trace={noGrants} />)
    expect(screen.getByText('No GRANTs exist.')).toBeInTheDocument()
  })
})
