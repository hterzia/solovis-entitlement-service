package com.solovis.entitlement.client;

import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import com.solovis.entitlement.core.engine.Decision;
import com.solovis.entitlement.core.engine.Explanation;
import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The public interface's only production implementation, constructed by {@code
 * EntitlementClientBuilder} (Task 13). Resolution is entirely local: {@link #check} and {@link
 * #checkAll} never open a socket and never throw for a service or network failure — the whole
 * point of the module.
 */
final class DefaultEntitlementClient implements EntitlementClient {

    private final AtomicReference<Replica> holder;
    private final SnapshotPoller poller;   // nullable: forTesting() supplies none
    private final FeedHttpClient feed;     // nullable: forTesting() supplies none
    private final Clock clock;
    private final ClientMetrics metrics;

    DefaultEntitlementClient(
        AtomicReference<Replica> holder, SnapshotPoller poller, FeedHttpClient feed, Clock clock, ClientMetrics metrics) {
        this.holder = holder;
        this.poller = poller;
        this.feed = feed;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** For tests: the decision path needs neither a poller nor a feed. */
    static DefaultEntitlementClient forTesting(AtomicReference<Replica> holder, Clock clock, ClientMetrics metrics) {
        return new DefaultEntitlementClient(holder, null, null, clock, metrics);
    }

    @Override
    public Decision check(String accountExternalId, String capabilityKey) {
        var replica = holder.get();            // one read; a sync mid-call cannot be observed half-applied
        var decision = Resolver.resolve(
            replica.snapshot(), accountExternalId, parseKey(capabilityKey), clock.instant());
        metrics.decision(capabilityKey, decision.allowed());
        return decision;
    }

    @Override
    public AccountEntitlements checkAll(String accountExternalId) {
        var replica = holder.get();            // one snapshot for every capability (c31)
        var snapshot = replica.snapshot();
        var evaluatedAt = clock.instant();     // one moment for every capability
        var assignment = snapshot.account(accountExternalId)
            .orElseThrow(() -> new UnknownAccountException(accountExternalId));
        var decisions = snapshot.activeCapabilities().stream()
            .sorted(Comparator.comparing(c -> c.key().value()))
            .map(c -> Resolver.resolve(snapshot, accountExternalId, c.key(), evaluatedAt))
            .toList();
        decisions.forEach(d -> metrics.decision(d.capabilityKey(), d.allowed()));
        return new AccountEntitlements(
            accountExternalId, assignment.planKey(), decisions, snapshot.snapshotVersion(), evaluatedAt);
    }

    @Override
    public Decision check(String accountExternalId, String capabilityKey, long minSnapshotVersion) {
        throw new UnsupportedOperationException("Task 12/13");
    }

    @Override
    public Explanation explain(String accountExternalId, String capabilityKey) {
        throw new UnsupportedOperationException("Task 12/13");
    }

    @Override
    public Optional<Capability> capability(String capabilityKey) {
        try {
            return holder.get().snapshot().capability(new CapabilityKey(capabilityKey));
        } catch (IllegalArgumentException e) {
            // An unparseable key and an unknown key are the same answer: no such capability.
            return Optional.empty();
        }
    }

    @Override
    public List<Capability> capabilities() {
        return List.copyOf(holder.get().snapshot().capabilities());
    }

    @Override
    public ClientHealth health() {
        var replica = holder.get();
        var snapshotAge = Duration.between(replica.publishedAt(), clock.instant());
        if (poller == null) {
            return new ClientHealth(
                replica.version(), replica.publishedAt(), snapshotAge, false, clock.instant(), Optional.empty());
        }
        var state = poller.state();
        return new ClientHealth(
            replica.version(), replica.publishedAt(), snapshotAge, state.stale(),
            state.lastSuccessfulSync(), Optional.ofNullable(state.lastError()));
    }

    @Override
    public boolean awaitVersion(long snapshotVersion, Duration timeout) {
        throw new UnsupportedOperationException("Task 12/13");
    }

    @Override
    public void close() {
        if (poller != null) {
            poller.close();
        }
        if (feed != null) {
            feed.close();
        }
    }

    /**
     * A malformed key and an unknown key are the same answer on the decision path: {@code
     * CapabilityKey}'s constructor throws {@link IllegalArgumentException} for anything not
     * matching its pattern, which is not one of the three domain errors this method promises.
     * Re-thrown as {@link UnknownCapabilityException} so callers only ever see the three.
     */
    private static CapabilityKey parseKey(String capabilityKey) {
        try {
            return new CapabilityKey(capabilityKey);
        } catch (IllegalArgumentException e) {
            throw new UnknownCapabilityException(capabilityKey);
        }
    }
}
