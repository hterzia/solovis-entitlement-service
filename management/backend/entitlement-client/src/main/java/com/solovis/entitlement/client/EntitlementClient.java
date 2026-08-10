package com.solovis.entitlement.client;

import com.solovis.entitlement.core.engine.Decision;
import com.solovis.entitlement.core.engine.Explanation;
import com.solovis.entitlement.core.model.Capability;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Answers, for one account and one capability, whether they are allowed and what the value is —
 * from a local replica, in microseconds, and while the management service is down.
 *
 * <p>Construct one per process and share it; {@code check} and {@code checkAll} are safe from any
 * thread and lock-free.
 *
 * <h2>Caller obligations</h2>
 * <ol>
 *   <li>Reuse an answer for no longer than 10 seconds, and do not layer a cache in front of
 *       {@code check()} — it is already an in-memory lookup (c29).
 *   <li>Do not persist decisions as durable state. An entitlement is a question you ask, not a
 *       fact you own.
 *   <li>Count usage yourself. This service publishes the limit and never knows consumption; a
 *       {@code QUANTITY} of 0 means none available right now, which is neither an error nor a denial.
 *   <li>Do not infer <em>why</em> from {@code allowed} (c18).
 *   <li>Surface staleness; never act on it.
 *   <li>Handle the three errors as errors, never as "denied" (c19).
 *   <li>Need several capabilities consistently? Call {@code checkAll} once (c31).
 *   <li>Acting on a change you just made? Pass its {@code minSnapshotVersion}.
 *   <li>Never cross a {@code resolverContract} bump without a coordinated rollout.
 * </ol>
 */
public interface EntitlementClient extends AutoCloseable {

    /** Configures and constructs a client. {@code build()} blocks until the first snapshot loads. */
    static EntitlementClientBuilder builder() {
        return new EntitlementClientBuilder();
    }

    /** One capability. Local, lock-free, microseconds. Never throws on service failure. */
    Decision check(String accountExternalId, String capabilityKey);

    /** Every non-retired capability for one account, resolved at one snapshot version (c31). */
    AccountEntitlements checkAll(String accountExternalId);

    /** As {@link #check}, but resolved at or above {@code minSnapshotVersion}. */
    Decision check(String accountExternalId, String capabilityKey, long minSnapshotVersion);

    /**
     * DIAGNOSTIC ONLY. Always calls the service; never resolves locally. Not for a request path:
     * it is a network call and it fails during an outage.
     */
    Explanation explain(String accountExternalId, String capabilityKey);

    /** The capability registry, including tier orders, for interpreting values. */
    Optional<Capability> capability(String capabilityKey);

    List<Capability> capabilities();

    /** Replica freshness. Surface this; do not branch on it for access decisions. */
    ClientHealth health();

    /** Opt-in: block until the replica reaches a version, or time out. Not a default. */
    boolean awaitVersion(long snapshotVersion, Duration timeout);

    @Override
    void close();
}
