import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/mocks/server'
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
    await expect(removeOverride('acct_9931', created.overrideId)).resolves.toMatchObject({ snapshotVersion: 48212 })
  })

  it('reassigns the plan and reports retained overrides', async () => {
    const result = await setAccountPlan('acct_9931', { planKey: 'free', source: 'PERSON', actor: 'a.reyes', reason: 'Downgrade' })
    expect(result.retainedOverrideCount).toBe(2)
  })

  // Keys and external ids go into the path, so anything with a reserved character must be encoded
  // or it silently changes which resource is addressed (or produces a malformed request).
  it('encodes a path parameter that contains reserved characters', async () => {
    let seen = ''
    server.use(
      http.get('/admin/v1/accounts/*', ({ request }) => {
        seen = new URL(request.url).pathname
        return HttpResponse.json({ account: 'x', name: null, status: 'ACTIVE', plan: {}, snapshotVersion: 1, entitlements: [], overrides: [] })
      }),
    )

    await getAccount('acct/9931')

    expect(seen).toBe('/admin/v1/accounts/acct%2F9931')
  })
})
