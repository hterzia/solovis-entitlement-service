import { apiGet } from './http'
import type { Decision } from '../types/domain'

export interface CheckParams {
  account?: string
  capability?: string
  override?: string
  /** An ISO date. Present only when the operator is asking about the past (002 §6). */
  asAt?: string
}

/**
 * A past answer is the decision payload with two extra fields, because the service unwraps it —
 * the checker renders one thing whether it is showing today or March, and `<TraceView>` needs no
 * second code path.
 */
export interface CheckResult extends Decision {
  asAt?: string
  capabilityRetiredSince?: string
}

export function checkDecision(params: CheckParams) {
  const search = new URLSearchParams()
  if (params.account) search.set('account', params.account)
  if (params.capability) search.set('capability', params.capability)
  if (params.override) search.set('override', params.override)
  if (params.asAt) search.set('asAt', params.asAt)
  return apiGet<CheckResult>(`/check?${search.toString()}`)
}
