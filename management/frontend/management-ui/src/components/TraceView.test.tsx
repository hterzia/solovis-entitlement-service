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

  it('marks the winning grant and the losing grant distinctly', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('grant-ovr_4471')).toHaveTextContent('WON')
    expect(screen.getByTestId('grant-ovr_2210')).toHaveTextContent('LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT')
  })

  it('shows the winning HOLD', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('hold-ovr_7788')).toHaveTextContent('WON')
  })

  it('renders the result value and allowed flag', () => {
    render(<TraceView trace={RESULT_TRACE} />)
    expect(screen.getByTestId('trace-result')).toHaveTextContent('0')
    expect(screen.getByTestId('trace-result')).toHaveTextContent('allowed: true')
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
