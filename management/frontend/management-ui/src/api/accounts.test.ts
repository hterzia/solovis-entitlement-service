import { describe, expect, it } from 'vitest'
import { listAccounts, getAccount, addOverride, removeOverride, setAccountPlan } from './accounts'
import { ApiError } from './http'

describe('accounts API', () => {
  it('gets an account with entitlements and overrides', async () => {
    const account = await getAccount('acct_9931')
    expect(account.plan.key).toBe('pro')
    expect(account.overrides).toHaveLength(2)
  })

  it('searches accounts by query', async () => {
    const { accounts } = await listAccounts({ q: 'northwind' })
    expect(accounts).toHaveLength(1)
  })

  it('rejects an override with no reason', async () => {
    await expect(
      addOverride('acct_9931', { capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 10 }, reason: '' }),
    ).rejects.toSatisfy((e) => e instanceof ApiError && e.problem.type === 'entitlement/reason-required')
  })

  it('creates and then removes an override', async () => {
    const created = await addOverride('acct_9931', {
      capability: 'reports.monthly', kind: 'GRANT', value: { type: 'QUANTITY', amount: 10 }, reason: 'Test grant',
    })
    await expect(removeOverride('acct_9931', created.override.id)).resolves.toMatchObject({ snapshotVersion: 48212 })
  })

  it('reassigns the plan and reports retained overrides', async () => {
    const result = await setAccountPlan('acct_9931', { planKey: 'free', source: 'PERSON', actor: 'a.reyes', reason: 'Downgrade' })
    expect(result.retainedOverrideCount).toBe(2)
  })
})
