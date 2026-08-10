import { ValueBadge } from './ValueEditor'
import type { Trace, TraceCandidate } from '../types/domain'
import type { CapabilityTier } from '../types/domain'

const OUTCOME_LABELS: Record<string, string> = {
  WON: 'won',
  LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT: 'lost — less generous',
  LOST_NOT_MORE_GENEROUS_THAN_PLAN: 'lost — less generous',
  LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD: 'lost — less restrictive',
  NOT_IN_FORCE_PENDING: 'not in force — not yet begun',
  NOT_IN_FORCE_ENDED: 'not in force — ended',
  NOT_IN_FORCE_REMOVED: 'not in force — removed',
}

const NOT_IN_FORCE = new Set(['NOT_IN_FORCE_PENDING', 'NOT_IN_FORCE_ENDED', 'NOT_IN_FORCE_REMOVED'])

/**
 * The date half of a not-in-force entry, in words — *ended 30 June*, *begins 1 October*, *removed
 * 12 May*. It reads the dates the service put on the entry and picks a phrase; it derives nothing,
 * which is the same rule this component has followed since v1.
 */
function notInForceNote(candidate: TraceCandidate): string | null {
  if (candidate.outcome === 'NOT_IN_FORCE_PENDING' && candidate.startsOn) {
    return `begins ${candidate.startsOn}`
  }
  if (candidate.outcome === 'NOT_IN_FORCE_ENDED' && candidate.expiresOn) {
    return `ended after ${candidate.expiresOn}`
  }
  if (candidate.outcome === 'NOT_IN_FORCE_REMOVED' && candidate.notInForceSince) {
    return `removed ${candidate.notInForceSince}`
  }
  return candidate.notInForceSince ? `not in force since ${candidate.notInForceSince}` : null
}

const ALLOWED_REASON_LABELS: Record<string, string> = {
  NO_OFF_VALUE_DECLARED: 'no off-value declared',
  DIFFERS_FROM_OFF_VALUE: 'differs from off-value',
  EQUALS_OFF_VALUE: 'equals off-value',
}

function CandidateRow({ candidate, tiers, testIdPrefix }: { candidate: TraceCandidate; tiers: CapabilityTier[]; testIdPrefix: string }) {
  const notInForce = NOT_IN_FORCE.has(candidate.outcome)
  const note = notInForce ? notInForceNote(candidate) : null
  return (
    <li
      data-testid={`${testIdPrefix}-${candidate.overrideId}`}
      data-not-in-force={notInForce ? 'true' : undefined}
      className={notInForce ? 'trace-candidate trace-candidate--not-in-force' : 'trace-candidate'}
    >
      <ValueBadge value={candidate.value} tiers={tiers} /> {candidate.overrideId} — {candidate.reason} — {candidate.createdBy}, {candidate.createdAt}{' '}
      <strong>{OUTCOME_LABELS[candidate.outcome] ?? candidate.outcome}</strong>
      {note && <span className="trace-candidate__window"> ({note})</span>}
    </li>
  )
}

const GRANT_ABSENCE_TEXT: Record<string, string> = {
  NO_GRANTS: 'No GRANTs exist.',
  // Deliberately different words from NO_GRANTS: "none in force" and "none at all" are different
  // facts, and telling an operator the second when the first is true is what c20 exists to prevent.
  NO_GRANTS_IN_FORCE: 'No GRANT is in force. Any listed above had not begun, had ended, or was removed.',
  PLAN_AT_LEAST_AS_GENEROUS: 'The plan is at least as generous as every GRANT, so the plan stands.',
}

const HOLD_ABSENCE_TEXT: Record<string, string> = {
  NO_HOLDS: 'No HOLDs exist.',
  NO_HOLDS_IN_FORCE: 'No HOLD is in force. Any listed above had not begun, had ended, or was removed.',
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
        {trace.grants.length > 0 && (
          <ul>{trace.grants.map((g) => <CandidateRow key={g.overrideId} candidate={g} tiers={tiers} testIdPrefix="grant" />)}</ul>
        )}
        {/* Also stated when candidates are listed but none counts: c20 wants the explanation alone
            to be enough to say why the answer changed, and a list of dimmed rows with no sentence
            leaves the operator to infer it. */}
        {(trace.grants.length === 0 || trace.grantStep.why === 'NO_GRANTS_IN_FORCE') && (
          <p>{GRANT_ABSENCE_TEXT[trace.grantStep.why ?? 'NO_GRANTS']}</p>
        )}
        {trace.grantStep.note && <p>{trace.grantStep.note}</p>}
      </section>

      <section>
        <h4>Holds</h4>
        {trace.holds.length > 0 && (
          <ul>{trace.holds.map((h) => <CandidateRow key={h.overrideId} candidate={h} tiers={tiers} testIdPrefix="hold" />)}</ul>
        )}
        {(trace.holds.length === 0 || trace.holdStep.why === 'NO_HOLDS_IN_FORCE') && (
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
