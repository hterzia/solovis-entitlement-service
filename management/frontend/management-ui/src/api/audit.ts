import { apiGet } from './http'
import type { AuditEvent } from '../types/domain'

export interface AuditQuery {
  account?: string
  planKey?: string
  actor?: string
  entityType?: string
  /** c31 — the history is filterable by capability. */
  capability?: string
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
