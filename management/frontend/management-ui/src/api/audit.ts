import { apiGet } from './http'
import type { AuditEvent } from '../types/domain'

export interface AuditQuery {
  account?: string
  planKey?: string
  actor?: string
  entityType?: string
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

export function listAuditEvents(params: AuditQuery) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined) search.set(k, String(v))
  })
  const qs = search.toString()
  return apiGet<{ events: AuditEvent[]; nextCursor: string | null }>(`/audit${qs ? `?${qs}` : ''}`)
}
