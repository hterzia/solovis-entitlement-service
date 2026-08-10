package com.solovis.entitlement.client;

/** What {@code build()} may do when no snapshot can be fetched before {@code startupTimeout}. */
public enum StartupMode {

    /**
     * Refuse to construct. The SDK will not guess: with no replica there is no last answer to carry
     * on with, and inventing one would either take away or grant.
     */
    REQUIRE_SNAPSHOT,

    /**
     * Start from the disk cache if one is readable, immediately {@code stale}, and keep polling.
     * This is what lets a customer's entitlements survive a restart during an outage.
     *
     * <p>Covers only an unreachable service — {@code startupTimeout} elapsing with no snapshot to
     * even evaluate. It does <b>not</b> cover a conformance-gate failure: a snapshot the service
     * did deliver but whose vectors this SDK's engine computes differently. That failure means the
     * resolution rule itself disagrees, which a disk-cached replica — resolved by that same
     * disagreeing engine — cannot fix. A gate failure always fails construction, in every {@code
     * StartupMode}, cache or no cache.
     */
    ALLOW_DISK_CACHE
}
