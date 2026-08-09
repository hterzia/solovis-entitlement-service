package com.solovis.entitlement.core.engine;

/**
 * Where a trace entry's value came from. {@code CAPABILITY_DEFAULT}/{@code PLAN} apply only to
 * the baseline entry (distinguishes a defaulted 0 from a plan 0, c22); {@code GRANT}/{@code HOLD}
 * apply only to override candidate entries. Matches the four-value vocabulary
 * contracts/admin-api.md documents for c39.
 */
public enum TraceSource {
    CAPABILITY_DEFAULT,
    PLAN,
    GRANT,
    HOLD
}
