import { apiGet } from './http'
import type { Decision } from '../types/domain'

export function checkDecision(params: { account?: string; capability?: string; override?: string }) {
  const search = new URLSearchParams()
  if (params.account) search.set('account', params.account)
  if (params.capability) search.set('capability', params.capability)
  if (params.override) search.set('override', params.override)
  return apiGet<Decision>(`/check?${search.toString()}`)
}
