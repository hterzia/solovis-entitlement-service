import { describe, expect, it } from 'vitest'
import { getMeta } from './meta'

describe('getMeta', () => {
  it('returns the service metadata used for the liveness promise', async () => {
    await expect(getMeta()).resolves.toEqual({
      changeVisibleEverywhereWithinSeconds: 60,
      answerReuseMaxSeconds: 10,
      snapshotVersion: 48211,
      capabilityAreas: ['api', 'export', 'reports', 'seats', 'support'],
    })
  })
})
