package com.solovis.entitlement.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Replica freshness. Surface it on a status page and alert on {@code snapshotAge}; never branch on
 * it for an access decision. Refusing access because a replica is stale would be exactly the
 * taking-away that spec §11 forbids.
 */
public record ClientHealth(
    long snapshotVersion,
    Instant snapshotPublishedAt,
    Duration snapshotAge,
    boolean stale,
    Instant lastSuccessfulSync,
    Optional<String> lastError) {}
