import { ValueBadge } from './ValueEditor'
import type { Trace, TraceCandidate } from '../types/domain'
import type { CapabilityTier } from '../types/domain'

const OUTCOME_LABELS: Record<string, string> = {
  WON: 'won',
  LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT: 'lost — less generous',
  LOST_NOT_MORE_GENEROUS_THAN_PLAN: 'lost — less generous',
  LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD: 'lost — less restrictive',
}

const ALLOWED_REASON_LABELS: Record<string, string> = {
  NO_OFF_VALUE_DECLARED: 'no off-value declared',
  DIFFERS_FROM_OFF_VALUE: 'differs from off-value',
  EQUALS_OFF_VALUE: 'equals off-value',
}

function CandidateRow({ candidate, tiers, testIdPrefix }: { candidate: TraceCandidate; tiers: CapabilityTier[]; testIdPrefix: string }) {
  return (
    <li data-testid={`${testIdPrefix}-${candidate.overrideId}`}>
      <ValueBadge value={candidate.value} tiers={tiers} /> {candidate.overrideId} — {candidate.reason} — {candidate.createdBy}, {candidate.createdAt}{' '}
      <strong>{OUTCOME_LABELS[candidate.outcome] ?? candidate.outcome}</strong>
    </li>
  )
}

const GRANT_ABSENCE_TEXT: Record<string, string> = {
  NO_GRANTS: 'No GRANTs exist.',
  PLAN_AT_LEAST_AS_GENEROUS: 'The plan is at least as generous as every GRANT, so the plan stands.',
}

const HOLD_ABSENCE_TEXT: Record<string, string> = {
  NO_HOLDS: 'No HOLDs exist.',
  HOLD_NOT_MORE_RESTRICTIVE: 'No HOLD is more restrictive than the result, so nothing was capped.',
}

export function TraceView({ trace, tiers = [] }: { trace: Trace; tiers?: CapabilityTier[] }) {
  return (
    <div className="trace-view">
      <section>
        <h4>Baseline</h4>
        <p>
          <ValueBadge value={trace.baseline.value} tiers={tiers} /> {trace.baseline.note}
        </p>
      </section>

      <section>
        <h4>Grants</h4>
        {trace.grants.length > 0 ? (
          <ul>{trace.grants.map((g) => <CandidateRow key={g.overrideId} candidate={g} tiers={tiers} testIdPrefix="grant" />)}</ul>
        ) : (
          <p>{GRANT_ABSENCE_TEXT[trace.grantStep.why ?? 'NO_GRANTS']}</p>
        )}
        {trace.grantStep.note && <p>{trace.grantStep.note}</p>}
      </section>

      <section>
        <h4>Holds</h4>
        {trace.holds.length > 0 ? (
          <ul>{trace.holds.map((h) => <CandidateRow key={h.overrideId} candidate={h} tiers={tiers} testIdPrefix="hold" />)}</ul>
        ) : (
          <p>{HOLD_ABSENCE_TEXT[trace.holdStep.why ?? 'NO_HOLDS']}</p>
        )}
        {trace.holdStep.note && <p>{trace.holdStep.note}</p>}
      </section>

      <section data-testid="trace-result">
        <h4>Result</h4>
        <p>
          <ValueBadge value={trace.result.value} tiers={tiers} /> · allowed: {String(trace.result.allowed)}
          {' '}({ALLOWED_REASON_LABELS[trace.result.allowedReason] ?? trace.result.allowedReason})
        </p>
      </section>
    </div>
  )
}
