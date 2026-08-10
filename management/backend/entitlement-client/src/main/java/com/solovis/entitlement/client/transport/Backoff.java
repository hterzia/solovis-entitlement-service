package com.solovis.entitlement.client.transport;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * The retry ladder a replica walks while the service is unreachable: 5 s, 10 s, 30 s, 60 s,
 * jittered, then holding. Expressed as multiples of the configured poll interval so the ladder
 * scales with it rather than hard-coding seconds.
 *
 * <p>Not thread-safe: one instance belongs to one poller thread.
 */
public final class Backoff {

    private static final int[] RUNGS = {1, 2, 6, 12};

    private final Duration base;
    private final LongUnaryOperator jitter;
    private int rung;

    public Backoff(Duration base, LongUnaryOperator jitter) {
        this.base = java.util.Objects.requireNonNull(base, "base");
        this.jitter = java.util.Objects.requireNonNull(jitter, "jitter");
    }

    /** Jitters uniformly within ±20% so a fleet of replicas does not retry in lockstep. */
    public Backoff(Duration base) {
        this(base, millis -> {
            long spread = Math.max(1L, millis / 5);
            return millis - spread + ThreadLocalRandom.current().nextLong(2 * spread + 1);
        });
    }

    public Duration nextDelay() {
        long millis = base.toMillis() * RUNGS[Math.min(rung, RUNGS.length - 1)];
        rung = Math.min(rung + 1, RUNGS.length - 1);
        return Duration.ofMillis(Math.max(0L, jitter.applyAsLong(millis)));
    }

    /** Called after a successful sync. */
    public void reset() {
        rung = 0;
    }
}
