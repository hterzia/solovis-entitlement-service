import { describe, expect, it } from 'vitest'
import { listPlans, previewPlanEntitlements, applyPlanEntitlements, archivePlan } from './plans'
import { ApiError } from './http'

describe('plans API', () => {
  it('lists plans with account counts and the default marker', async () => {
    const { plans } = await listPlans()
    expect(plans.find((p) => p.key === 'free')?.isDefaultForNewAccounts).toBe(true)
  })

  it('previews an entitlement change with the affected count and a diff', async () => {
    const preview = await previewPlanEntitlements('pro', {
      set: { 'reports.monthly': { type: 'QUANTITY', amount: 75 } }, unset: [], previewAccount: 'acct_9931',
    })
    expect(preview.affectedAccountCount).toBe(26890)
    expect(preview.diff[0]).toMatchObject({ capability: 'reports.monthly' })
    expect(preview.previewAccount?.effects[0].changed).toBe(false)
  })

  it('rejects applying without a valid preview token', async () => {
    await expect(
      applyPlanEntitlements('pro', { set: {}, unset: [], previewToken: 'stale' }),
    ).rejects.toSatisfy((e) => e instanceof ApiError && e.problem.status === 409)
  })

  it('refuses to archive a plan with accounts on it', async () => {
    await expect(archivePlan('pro')).rejects.toSatisfy(
      (e) => e instanceof ApiError && e.problem.type === 'entitlement/plan-in-use',
    )
  })
})
