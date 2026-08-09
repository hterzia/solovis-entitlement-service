package com.solovis.entitlement.core.conformance;

import com.solovis.entitlement.core.engine.Resolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs a set of {@link ConformanceVector}s against this JVM's {@link Resolver} (research.md §20). */
public final class ConformanceCheck {

    private ConformanceCheck() {}

    public static ConformanceResult run(List<ConformanceVector> vectors) {
        var failures = new ArrayList<String>();
        for (var vector : vectors) {
            var decision = Resolver.resolve(vector.fixture(), vector.accountExternalId(), vector.capabilityKey(), Instant.now());
            if (decision.allowed() != vector.expectedAllowed() || !decision.value().equals(vector.expectedValue())) {
                failures.add(vector.name() + ": expected allowed=" + vector.expectedAllowed()
                    + " value=" + vector.expectedValue()
                    + " but got allowed=" + decision.allowed() + " value=" + decision.value());
            }
        }
        return new ConformanceResult(failures.isEmpty(), List.copyOf(failures));
    }

    public record ConformanceResult(boolean passed, List<String> failures) {}
}
