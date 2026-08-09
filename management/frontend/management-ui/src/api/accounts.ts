import { apiGet, apiPost, apiPut, apiDelete } from './http'
import type { EntitlementValue } from '../types/value'
import type { AccountDetail, AccountSummary, AssignmentSource, Decision, Override, OverrideKind } from '../types/domain'

export function listAccounts(params?: { q?: string; planKey?: string; cursor?: string }) {
  const search = new URLSearchParams()
  if (params?.q) search.set('q', params.q)
  if (params?.planKey) search.set('planKey', params.planKey)
  if (params?.cursor) search.set('cursor', params.cursor)
  const qs = search.toString()
  return apiGet<{ accounts: AccountSummary[]; nextCursor: string | null }>(`/accounts${qs ? `?${qs}` : ''}`)
}

export function createAccount(input: { externalId: string; name?: string }) {
  return apiPost<AccountSummary>('/accounts', input)
}

export function getAccount(external: string) {
  return apiGet<AccountDetail>(`/accounts/${external}`)
}

export function setAccountPlan(external: string, input: { planKey: string; source: AssignmentSource; actor: string; reason?: string }) {
  return apiPut<AccountDetail & { retainedOverrideCount: number }>(`/accounts/${external}/plan`, input)
}

export interface AddOverrideInput {
  capability: string
  kind: OverrideKind
  value: EntitlementValue
  reason: string
}

export function addOverride(external: string, input: AddOverrideInput) {
  return apiPost<{ override: Override; decision: Decision; snapshotVersion: number; changeVisibleEverywhereWithinSeconds: number }>(
    `/accounts/${external}/overrides`,
    input,
  )
}

export function removeOverride(external: string, id: string, reason?: string) {
  return apiDelete<{ decision: Decision; snapshotVersion: number }>(`/accounts/${external}/overrides/${id}`, reason ? { reason } : undefined)
}
