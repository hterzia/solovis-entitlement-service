package com.solovis.entitlement.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The replication feed's retention settings (data-model.md, {@code snapshot_version}).
 *
 * @param deltaRetention how far back delta rows are kept. A replica asking for a version older than
 *     this is told to full-resync ({@code 410 entitlement/snapshot-too-old}). snapshot-feed.md
 *     states seven days, which is the default; it is a configuration knob rather than a constant
 *     because the right horizon depends on how long a consuming service may plausibly stay down.
 * @param pruneInterval how often the pruner sweeps. Nothing depends on promptness — an unpruned row
 *     is wasted space, never a wrong answer — so this is deliberately infrequent.
 * @param pruneEnabled the sweep's off switch, for tests and for anyone who would rather keep every
 *     version for forensics and accept the growth.
 */
@ConfigurationProperties(prefix = "entitlement.snapshot")
public record EntitlementSnapshotProperties(
        @DefaultValue("7d") Duration deltaRetention,
        @DefaultValue("1h") Duration pruneInterval,
        @DefaultValue("true") boolean pruneEnabled) {

    public EntitlementSnapshotProperties {
        if (deltaRetention == null || deltaRetention.isNegative() || deltaRetention.isZero()) {
            throw new IllegalArgumentException("entitlement.snapshot.delta-retention must be a positive duration");
        }
        if (pruneInterval == null || pruneInterval.isNegative() || pruneInterval.isZero()) {
            throw new IllegalArgumentException("entitlement.snapshot.prune-interval must be a positive duration");
        }
    }
}
