package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.core.conformance.ConformanceCheck;
import com.solovis.entitlement.core.conformance.ResolverContract;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a candidate replica's own conformance vectors with this SDK's engine before the
 * replica is allowed to serve.
 *
 * <p>This is the primary defence against two replicas on different SDK versions answering
 * differently for the same account. It has to be proactive: with no local traces, a wrong answer
 * leaves nothing to diagnose after the fact.
 */
public final class ConformanceGate {

    /** The wire format this SDK knows how to read. */
    public static final int SUPPORTED_FORMAT = 1;

    private ConformanceGate() {}

    public record GateResult(boolean passed, String reason) {
        public static GateResult ok() {
            return new GateResult(true, "");
        }
    }

    /**
     * Equivalent to {@link #evaluate(Replica, boolean) evaluate(candidate, false)} — does not
     * require the candidate to carry vectors. Kept so a delta-derived candidate (which inherits
     * {@code current.vectors()} rather than fetching its own) keeps passing through unchanged.
     */
    public static GateResult evaluate(Replica candidate) {
        return evaluate(candidate, false);
    }

    /**
     * @param requireVectors when {@code true}, a candidate carrying zero conformance vectors
     *     fails the gate instead of passing vacuously. Pass {@code true} only for a candidate that
     *     came straight from {@code GET /v1/snapshot/full} — a delta-derived candidate inherits its
     *     predecessor's vectors and was never supposed to carry its own (see {@code
     *     DeltaApplier.apply}), so it must never be held to this. Without this, a service-side
     *     regression that stopped emitting {@code conformance} lines would silently disable this
     *     module's primary drift defence, with no signal that it had gone quiet.
     */
    public static GateResult evaluate(Replica candidate, boolean requireVectors) {
        if (candidate.format() != SUPPORTED_FORMAT) {
            return new GateResult(false, "Unsupported feed format " + candidate.format()
                + "; this SDK reads format " + SUPPORTED_FORMAT + ".");
        }
        if (candidate.resolverContract() != ResolverContract.VERSION) {
            return new GateResult(false, "Unsupported resolverContract " + candidate.resolverContract()
                + "; this SDK implements " + ResolverContract.VERSION
                + ". The resolution rule itself changed — this needs a coordinated rollout.");
        }
        if (requireVectors && candidate.vectors().isEmpty()) {
            return new GateResult(false,
                "A full snapshot carried zero conformance vectors; refusing to serve a replica "
                    + "whose drift defence cannot be evaluated rather than passing vacuously.");
        }

        // Evaluated one vector at a time, not via ConformanceCheck.run(candidate.vectors()): core's
        // run() does not catch exceptions from a vector, so a fixture naming an account it doesn't
        // contain would propagate straight out of the gate instead of being reported as a named
        // failure. The loop keeps every failure attributable to a vector name, which is the whole
        // diagnostic value of the gate.
        var failures = new ArrayList<String>();
        for (var vector : candidate.vectors()) {
            try {
                var single = ConformanceCheck.run(List.of(vector));
                failures.addAll(single.failures());
            } catch (RuntimeException e) {
                failures.add(vector.name() + ": could not be evaluated — " + e);
            }
        }
        return failures.isEmpty()
            ? GateResult.ok()
            : new GateResult(false, "Conformance vectors failed: " + String.join("; ", failures));
    }
}
