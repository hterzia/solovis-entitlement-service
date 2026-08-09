import { apiGet, apiPost, apiPatch } from './http'
import type { Capability, CapabilityTier } from '../types/domain'

export function listCapabilities(params?: { area?: string; q?: string; status?: 'ACTIVE' | 'RETIRED' | 'ALL' }) {
  const search = new URLSearchParams()
  if (params?.area) search.set('area', params.area)
  if (params?.q) search.set('q', params.q)
  if (params?.status) search.set('status', params.status)
  const qs = search.toString()
  return apiGet<{ capabilities: Capability[]; snapshotVersion: number }>(`/capabilities${qs ? `?${qs}` : ''}`)
}

export function getCapability(key: string) {
  return apiGet<Capability & { usage: { plans: string[]; liveOverrides: number } }>(`/capabilities/${key}`)
}

export interface CreateCapabilityInput {
  key: string
  displayName: string
  description: string | null
  valueType: Capability['valueType']
  default: Capability['default']
  offValue: Capability['offValue']
  tiers: { tier: string; displayName: string }[] | null
}

export function createCapability(input: CreateCapabilityInput) {
  return apiPost<Capability>('/capabilities', input)
}

export function updateCapability(key: string, patch: Partial<Pick<Capability, 'displayName' | 'description' | 'default' | 'offValue'>>) {
  return apiPatch<Capability>(`/capabilities/${key}`, patch)
}

export function addCapabilityTier(key: string, tier: { tier: string; displayName: string }) {
  return apiPost<Capability>(`/capabilities/${key}/tiers`, tier)
}

export function retireCapability(key: string) {
  return apiPost<Capability & { usage: { plans: string[]; liveOverrides: number } }>(`/capabilities/${key}/retire`)
}

export type { CapabilityTier }
