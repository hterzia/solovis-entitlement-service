package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One named step in a {@link Trace}: the baseline, or one candidate GRANT/HOLD. {@code planKey}
 * is present only on a PLAN-sourced baseline; {@code overrideId}/{@code reason}/{@code
 * createdBy}/{@code createdAt}/{@code outcome} are present only on a GRANT/HOLD candidate.
 */
public record TraceEntry(
    TraceSource source,
    OptionalLong overrideId,
    Optional<String> planKey,
    EntitlementValue value,
    Optional<String> reason,
    Optional<String> createdBy,
    Optional<Instant> createdAt,
    Optional<Outcome> outcome
) {}
