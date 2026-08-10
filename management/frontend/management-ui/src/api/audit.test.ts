import { describe, expect, it } from 'vitest'
import { listAuditEvents } from './audit'

describe('audit API', () => {
  it('lists audit events newest first', async () => {
    const { events } = await listAuditEvents({})
    expect(events.map((e) => e.seq)).toEqual([90114, 90113, 90112, 90111])
  })

  it('records the affected-account count on a plan-entitlement edit', async () => {
    const { events } = await listAuditEvents({})
    expect(events.find((e) => e.entityType === 'PLAN_ENTITLEMENT')?.affectedAccountCount).toBe(26890)
  })
})
