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
     */
    ALLOW_DISK_CACHE
}
