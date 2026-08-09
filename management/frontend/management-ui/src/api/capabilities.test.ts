import { describe, expect, it } from 'vitest'
import { listCapabilities, getCapability, createCapability, retireCapability } from './capabilities'
import { ApiError } from './http'

describe('capabilities API', () => {
  it('lists active capabilities by default', async () => {
    const { capabilities } = await listCapabilities()
    expect(capabilities.map((c) => c.key)).toContain('reports.monthly')
  })

  it('gets one capability by key', async () => {
    const cap = await getCapability('support')
    expect(cap.tiers.map((t) => t.tier)).toEqual(['community', 'standard', 'gold'])
  })

  it('rejects an unknown capability with entitlement/unknown-capability', async () => {
    await expect(getCapability('nope.nope')).rejects.toSatisfy(
      (e) => e instanceof ApiError && e.problem.type === 'entitlement/unknown-capability',
    )
  })

  it('creates a capability', async () => {
    const created = await createCapability({
      key: 'integration.salesforce', displayName: 'Salesforce integration', description: null,
      valueType: 'SWITCH', default: { type: 'SWITCH', enabled: false }, offValue: null, tiers: [],
    })
    expect(created.area).toBe('integration')
  })

  it('retires a capability and reports usage', async () => {
    const retired = await retireCapability('reports.monthly')
    expect(retired.capability.status).toBe('RETIRED')
    expect(retired.usage.liveOverrides).toBe(1)
  })
})
