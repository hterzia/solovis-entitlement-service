package com.solovis.entitlement.client.metrics;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The metrics seam. The SDK always calls it; whether anything is recorded depends on whether the
 * embedding product supplied a Micrometer registry. Keeping it an interface is what lets Micrometer
 * stay an optional dependency.
 */
public interface ClientMetrics {

    ClientMetrics NO_OP = new ClientMetrics() {};

    /** Convergence across replicas. */
    default void snapshotVersion(long version) {}

    /** Registers a gauge reading the replica's age. Called once, at construction. */
    default void snapshotAge(Supplier<Duration> age) {}

    /** Service reachability from this caller. */
    default void syncFailed() {}

    /** A replica falling behind the delta horizon. */
    default void fullResync() {}

    /** The drift gate firing. */
    default void conformanceFailed() {}

    /** Alert on disagreement across replicas — a straddled rollout, visible before it is a ticket. */
    default void resolverContract(int contract) {}

    /** Which capabilities actually gate anything. */
    default void decision(String capabilityKey, boolean allowed) {}

    /** Unknown-account races; a sustained rise means replicas are lagging. */
    default void readThrough() {}
}
