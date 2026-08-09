import { apiGet } from './http'

export interface ServiceMeta {
  changeVisibleEverywhereWithinSeconds: number
  answerReuseMaxSeconds: number
  snapshotVersion: number
  capabilityAreas: string[]
}

export function getMeta(): Promise<ServiceMeta> {
  return apiGet<ServiceMeta>('/meta')
}
