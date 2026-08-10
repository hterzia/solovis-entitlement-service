import { apiGet, apiPost, apiPut, apiDelete } from './http'
import { enc } from './path'
import type { EntitlementValue } from '../types/value'
import type { AccountDetail, AccountSummary, AssignmentSource, Decision, OverrideKind } from '../types/domain'

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
  return apiGet<AccountDetail>(`/accounts/${enc(external)}`)
}

export interface PlanReassignResult {
  account: string
  planKey: string
  retainedOverrideCount: number
  snapshotVersion: number
}

export function setAccountPlan(external: string, input: { planKey: string; source: AssignmentSource; actor: string; reason?: string }) {
  return apiPut<PlanReassignResult>(`/accounts/${enc(external)}/plan`, input)
}

export interface AddOverrideInput {
  capability: string
  kind: OverrideKind
  value: EntitlementValue
  reason: string
  /** ISO dates in the service zone; both optional, and blank is still the fastest path (002 c1). */
  startsOn?: string
  expiresOn?: string
}

export interface OverrideMutationResult {
  overrideId: string
  decision: Decision
  snapshotVersion: number
  changeVisibleEverywhereWithinSeconds: number
}

export function addOverride(external: string, input: AddOverrideInput) {
  return apiPost<OverrideMutationResult>(`/accounts/${enc(external)}/overrides`, input)
}

/**
 * What the decision becomes if this override were removed, for the confirmation shown *before* the
 * operator commits *(c14, c15)*. Read-only: it publishes nothing and audits nothing.
 *
 * The service answers this, not the SPA. Working it out here would mean re-running §4's combining
 * rule over the remaining overrides in TypeScript — a second implementation of the one rule this
 * service exists to centralise (`DECISIONS.md` §2).
 */
export function previewOverrideRemoval(external: string, id: string) {
  return apiGet<Decision>(`/accounts/${enc(external)}/overrides/${enc(id)}/removal-preview`)
}

export function removeOverride(external: string, id: string, reason?: string) {
  return apiDelete<OverrideMutationResult>(`/accounts/${enc(external)}/overrides/${enc(id)}`, reason ? { reason } : undefined)
}
