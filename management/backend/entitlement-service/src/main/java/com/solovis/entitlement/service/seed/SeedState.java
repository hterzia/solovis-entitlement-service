package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.store.ServiceStateRepository;
import com.solovis.entitlement.service.time.Timestamps;

import java.time.Clock;
import java.util.Optional;

/**
 * Whether this database has been seeded, recorded rather than inferred.
 *
 * <p>The previous check asked "are there any plans?", which is one question standing in for a
 * hundred and sixty writes: a crash after the first plan left a permanently half-populated demo
 * that every later boot skipped in silence. Two markers make the half-finished case nameable, and
 * {@link DemoDataSeeder} refuses to start on it.
 *
 * <p>That refusal matters more than it looks. The seed writes a backdated history, and
 * {@code seq} order and {@code occurred_at} order in {@code audit_event} must agree or a
 * point-in-time question silently returns today's answer. A backdated history therefore cannot be
 * appended to a database that already has one — it must be written in time order into an empty
 * database, or not at all.
 *
 * <p>Lives in {@code service_state} because that table exists for facts the service remembers about
 * itself, and is deliberately never pruned.
 */
public class SeedState {

    static final String STARTED = "seed.started";
    static final String COMPLETED = "seed.completed";

    public enum Status { ABSENT, STARTED, COMPLETED }

    private final ServiceStateRepository repository;
    private final Clock clock;

    public SeedState(ServiceStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Status status() {
        if (repository.find(COMPLETED).isPresent()) {
            return Status.COMPLETED;
        }
        return repository.find(STARTED).isPresent() ? Status.STARTED : Status.ABSENT;
    }

    public Optional<String> startedFingerprint() {
        return repository.find(STARTED);
    }

    public void markStarted(String fingerprint) {
        repository.put(STARTED, fingerprint, Timestamps.iso(clock.instant()));
    }

    public void markCompleted(String fingerprint) {
        repository.put(COMPLETED, fingerprint, Timestamps.iso(clock.instant()));
    }
}
