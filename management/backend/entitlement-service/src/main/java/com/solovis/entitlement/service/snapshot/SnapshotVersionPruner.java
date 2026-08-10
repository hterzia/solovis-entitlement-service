package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.config.EntitlementSnapshotProperties;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.time.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Enforces the delta-retention horizon on {@code snapshot_version} (data-model.md).
 *
 * <p>Why this table and not the audit trail: a version row exists so a replica can be carried from
 * <em>V</em> to <em>V+1</em>. Once no replica could still be that far behind, the row answers a
 * question nobody will ask. The audit trail is the opposite — §8 forbids ever removing from it, and
 * SQLite triggers enforce that — so the two must never share a retention story.
 *
 * <p>Pruning is what makes {@code 410 entitlement/snapshot-too-old} reachable, and therefore what
 * makes the SDK's full-resync path a real behaviour rather than a documented intention. A replica
 * that falls past the horizon is told to discard and refetch, which is always safe: the full
 * snapshot is self-contained and footer-checked.
 *
 * <p>Runs on a timer rather than inside {@link SnapshotPublisher}: publish happens inside the write
 * transaction on the one writer connection, and adding a {@code DELETE} to that path would put
 * table maintenance on the latency budget of every operator save for no benefit — nothing goes wrong
 * if a dead row survives another hour.
 */
@Component
public class SnapshotVersionPruner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotVersionPruner.class);

    private final SnapshotVersionRepository snapshotVersionRepository;
    private final SnapshotHolder snapshotHolder;
    private final EntitlementSnapshotProperties properties;
    private final Clock clock;

    public SnapshotVersionPruner(SnapshotVersionRepository snapshotVersionRepository, SnapshotHolder snapshotHolder,
            EntitlementSnapshotProperties properties, Clock clock) {
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.snapshotHolder = snapshotHolder;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${entitlement.snapshot.prune-interval:1h}",
               initialDelayString = "${entitlement.snapshot.prune-interval:1h}")
    public void pruneOnSchedule() {
        if (!properties.pruneEnabled()) {
            return;
        }
        try {
            int removed = prune();
            if (removed > 0) {
                log.info("Pruned {} snapshot_version rows published more than {} ago.", removed, retention());
            }
        } catch (RuntimeException e) {
            // Retention is housekeeping. A failed sweep must never take the service down or
            // interrupt the decision path, which does not read this table at all.
            log.warn("Snapshot version pruning failed; will retry on the next sweep.", e);
        }
    }

    /**
     * Removes every delta row past the horizon except the current version, and returns how many
     * went. Separate from the scheduled entry point so it can be driven directly by a test or an
     * operator without waiting for a timer.
     */
    @Transactional
    public int prune() {
        // The protected row is the one belonging to the version currently being served, not simply
        // the highest row in the table. SnapshotPublisher derives the next version from the
        // in-memory snapshot rather than from the table's autoincrement, so "latest row" and
        // "version the feed is answering with" are two different questions; only the second one
        // names the row GET /v1/snapshot/version reads publishedAt from.
        long serving = snapshotHolder.current().snapshotVersion();
        String cutoff = Timestamps.iso(clock.instant().minus(retention()));
        return snapshotVersionRepository.deleteOlderThan(cutoff, serving);
    }

    public Duration retention() {
        return properties.deltaRetention();
    }
}
