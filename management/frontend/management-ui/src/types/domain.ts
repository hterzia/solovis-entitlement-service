import type { EntitlementValue, ValueType } from './value'

export interface CapabilityTier {
  tier: string
  ordinal: number
  displayName: string
}

export type CapabilityStatus = 'ACTIVE' | 'RETIRED'

export interface Capability {
  key: string
  area: string
  displayName: string
  description: string | null
  valueType: ValueType
  default: EntitlementValue
  offValue: EntitlementValue | null
  tiers: CapabilityTier[]
  status: CapabilityStatus
}

export type PlanStatus = 'ACTIVE' | 'ARCHIVED'

export interface Plan {
  key: string
  name: string
  description?: string | null
  status: PlanStatus
  isDefaultForNewAccounts: boolean
  accountCount: number
  entitlementCount: number
}

export interface PlanEntitlement {
  capability: string
  value: EntitlementValue
}

export interface PlanEntitlementDiffEntry {
  capability: string
  before: EntitlementValue | null
  after: EntitlementValue | null
  note?: string
}

export type AssignmentSource = 'PERSON' | 'SYSTEM'

export type OverrideKind = 'GRANT' | 'HOLD'

export type OverrideEffect =
  | 'WINNING'
  | 'OVERRIDDEN_BY_HOLD'
  | 'SUPERSEDED_BY_GRANT'
  | 'SUPERSEDED_BY_STRICTER_HOLD'
  | 'NO_EFFECT_PLAN_MORE_GENEROUS'
  | 'NO_EFFECT_NOT_MORE_RESTRICTIVE'

/**
 * What an override is doing at one moment (002 c18). The service computes it — the SPA must never
 * derive `ENDED` by comparing `expiresOn` to the browser's clock, which would put a second
 * implementation of a date rule in a different time zone from the service's.
 */
export type OverrideStanding = 'IN_FORCE' | 'PENDING' | 'ENDED' | 'REMOVED'

export interface Override {
  id: string
  capability: string
  kind: OverrideKind
  value: EntitlementValue
  reason: string
  createdBy: string
  createdAt: string
  /** Only ever set while the override is IN_FORCE; `standing` describes every other state. */
  effectNow: OverrideEffect | null
  startsOn?: string | null
  expiresOn?: string | null
  standing: OverrideStanding
}

export type EntitlementSource = 'CAPABILITY_DEFAULT' | 'PLAN' | 'GRANT' | 'HOLD'

export interface EntitlementRow {
  capability: string
  area: string
  allowed: boolean
  value: EntitlementValue
  source: EntitlementSource
  sourceDetail: { overrideId?: string; reason?: string; planKey?: string } | null
}

export interface AccountDetail {
  account: string
  name: string | null
  status: 'ACTIVE' | 'CLOSED'
  plan: {
    key: string
    name: string
    assignedAt: string
    assignedBy: string
    source: AssignmentSource
  }
  snapshotVersion: number
  entitlements: EntitlementRow[]
  overrides: Override[]
}

export interface AccountSummary {
  account: string
  name: string | null
  planKey: string
  status: 'ACTIVE' | 'CLOSED'
}

export type TraceBaselineSource = 'PLAN' | 'CAPABILITY_DEFAULT'

export interface TraceCandidate {
  overrideId: string
  value: EntitlementValue
  reason: string
  createdBy: string
  createdAt: string
  /** Includes the three `NOT_IN_FORCE_*` outcomes since 002. Typed as `string` since v1, so
   *  new outcomes need new labels rather than a type change. */
  outcome: string
  startsOn?: string | null
  expiresOn?: string | null
  /** The day it stopped counting: expiry-day-plus-one, or the day it was removed (c20). */
  notInForceSince?: string | null
}

export interface Trace {
  baseline: {
    source: TraceBaselineSource
    planKey?: string
    value: EntitlementValue
    note: string
  }
  grants: TraceCandidate[]
  grantStep: { applied: boolean; winner?: string; value?: EntitlementValue; why?: string; note?: string }
  holds: TraceCandidate[]
  holdStep: { applied: boolean; winner?: string; value?: EntitlementValue; why?: string; note?: string }
  result: {
    value: EntitlementValue
    allowed: boolean
    allowedReason: 'NO_OFF_VALUE_DECLARED' | 'DIFFERS_FROM_OFF_VALUE' | 'EQUALS_OFF_VALUE'
  }
}

export interface Decision {
  account: string
  capability: string
  allowed: boolean
  value: EntitlementValue
  snapshotVersion: number
  evaluatedAt: string
  trace: Trace
}

export type AuditActorKind = 'PERSON' | 'SYSTEM'
export type AuditSource = 'UI' | 'BILLING' | 'API' | 'SEED'
export type AuditEntityType =
  | 'CAPABILITY' | 'CAPABILITY_TIER' | 'PLAN' | 'PLAN_ENTITLEMENT'
  | 'ACCOUNT' | 'ACCOUNT_PLAN' | 'DEFAULT_PLAN' | 'OVERRIDE'
export type AuditAction = 'CREATE' | 'UPDATE' | 'RETIRE' | 'ARCHIVE' | 'REMOVE' | 'ASSIGN' | 'DESIGNATE'

export interface AuditEvent {
  seq: number
  occurredAt: string
  actor: { id: string; kind: AuditActorKind }
  source: AuditSource
  entityType: AuditEntityType
  entityId: string
  action: AuditAction
  planKey: string | null
  account: string | null
  capability: string | null
  // The audit write path logs whatever shape is natural to the change being recorded — a bare
  // EntitlementValue for an override or plan-entitlement edit, a capability descriptor for a
  // capability create/update, a `{planKey}` map for a plan reassignment, and so on. There is no
  // single shape here; see types/value.ts's formatAuditValue for how it's rendered generically.
  before: unknown
  after: unknown
  reason: string | null
  affectedAccountCount: number | null
}
