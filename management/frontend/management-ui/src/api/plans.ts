import { apiGet, apiPost, apiPut } from './http'
import { enc } from './path'
import type { EntitlementValue } from '../types/value'
import type { Plan, PlanEntitlementDiffEntry, Trace } from '../types/domain'

export function listPlans() {
  return apiGet<{ plans: Plan[] }>('/plans')
}

export function createPlan(input: { key: string; name: string; description?: string }) {
  return apiPost<Plan>('/plans', input)
}

export function getPlan(key: string) {
  return apiGet<Plan & { entitlements: Record<string, EntitlementValue> }>(`/plans/${enc(key)}`)
}

export interface PlanEntitlementEditInput {
  set: Record<string, EntitlementValue>
  unset: string[]
  previewAccount?: string
}

export interface PlanPreviewResult {
  planKey: string
  affectedAccountCount: number
  diff: PlanEntitlementDiffEntry[]
  previewAccount?: {
    account: string
    effects: {
      capability: string
      before: { allowed: boolean; value: EntitlementValue; trace: Trace }
      after: { allowed: boolean; value: EntitlementValue; trace: Trace }
      changed: boolean
      note?: string
    }[]
  }
  previewToken: string
}

export function previewPlanEntitlements(key: string, input: PlanEntitlementEditInput) {
  return apiPost<PlanPreviewResult>(`/plans/${enc(key)}/entitlements/preview`, input)
}

export interface PlanApplyResult {
  planKey: string
  affectedAccountCount: number
  snapshotVersion: number
  auditSeq: number
  changeVisibleEverywhereWithinSeconds: number
}

export function applyPlanEntitlements(key: string, input: { set: Record<string, EntitlementValue>; unset: string[]; previewToken: string }) {
  return apiPut<PlanApplyResult>(`/plans/${enc(key)}/entitlements`, input)
}

export function archivePlan(key: string) {
  return apiPost<Plan>(`/plans/${enc(key)}/archive`)
}

export function setDefaultPlan(planKey: string) {
  return apiPut<{ planKey: string }>('/settings/default-plan', { planKey })
}
