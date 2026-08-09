package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One named step in a {@link Trace}: the baseline, or one candidate GRANT/HOLD. {@code planKey}
 * is present only on a PLAN-sourced baseline; {@code overrideId}/{@code reason}/{@code
 * createdBy}/{@code createdAt}/{@code outcome} are present only on a GRANT/HOLD candidate.
 *
 * <p>{@code startsOn}, {@code expiresOn} and {@code notInForceSince} carry the window an override
 * was granted under and, where it has stopped counting, the date it did (002 c19, c20). They are
 * copied from the {@link com.solovis.entitlement.core.model.StandingOverride} the resolver was
 * given rather than recomputed here — a trace reports standing, it does not decide it.
 */
public record TraceEntry(
    TraceSource source,
    OptionalLong overrideId,
    Optional<String> planKey,
    EntitlementValue value,
    Optional<String> reason,
    Optional<String> createdBy,
    Optional<Instant> createdAt,
    Optional<Outcome> outcome,
    Optional<LocalDate> startsOn,
    Optional<LocalDate> expiresOn,
    Optional<LocalDate> notInForceSince
) {

    /** An entry carrying no window — the baseline, and any override created before 002. */
    public TraceEntry(
        TraceSource source,
        OptionalLong overrideId,
        Optional<String> planKey,
        EntitlementValue value,
        Optional<String> reason,
        Optional<String> createdBy,
        Optional<Instant> createdAt,
        Optional<Outcome> outcome
    ) {
        this(source, overrideId, planKey, value, reason, createdBy, createdAt, outcome,
            Optional.empty(), Optional.empty(), Optional.empty());
    }
}
