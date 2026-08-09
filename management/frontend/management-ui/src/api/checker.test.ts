import { describe, expect, it } from 'vitest'
import { checkDecision } from './checker'

describe('checker API', () => {
  it('returns a decision with a full trace', async () => {
    const decision = await checkDecision({ account: 'acct_9931', capability: 'reports.monthly' })
    expect(decision.trace.result.allowed).toBe(true)
    expect(decision.trace.grants).toHaveLength(2)
  })
})
