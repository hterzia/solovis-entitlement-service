package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The full §6.1 explanation: baseline, every candidate GRANT and HOLD with its outcome, which
 * one (if any) won each group, and the result. Empty {@code grants}/{@code holds} together with
 * an empty winner is how "no grants at all" is distinguished from "grants existed but the plan
 * already beat them" — the caller building the wire {@code why} field reads exactly that.
 */
public record Trace(
    TraceEntry baseline,
    List<TraceEntry> grants,
    Optional<TraceEntry> grantWinner,
    List<TraceEntry> holds,
    Optional<TraceEntry> holdWinner,
    EntitlementValue result,
    boolean allowed
) {

    public Trace {
        Objects.requireNonNull(baseline, "baseline");
        grants = List.copyOf(grants);
        Objects.requireNonNull(grantWinner, "grantWinner");
        holds = List.copyOf(holds);
        Objects.requireNonNull(holdWinner, "holdWinner");
        Objects.requireNonNull(result, "result");
    }
}
