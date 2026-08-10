package com.solovis.entitlement.service.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ConformanceAnnouncer} once at startup, which is the only moment the compiled vector
 * set can have changed.
 *
 * <p>It needs no ordering dependency of its own. {@link SnapshotPublisher} derives the version it
 * publishes from the {@code snapshot_version} table's own autoincrement rather than from anything
 * held in memory, so the only precondition is a migrated schema — which context refresh already
 * guarantees before any {@code InitializingBean} runs.
 *
 * <p>An {@code InitializingBean} rather than an {@code ApplicationRunner}: runners fire after the web
 * connector is already accepting traffic. Nothing breaks if a replica polls in that window — it would
 * simply see the announcement one poll later — but there is no reason to leave the window open.
 */
@Component
public class ConformanceAnnouncementStartup implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ConformanceAnnouncementStartup.class);

    private final ConformanceAnnouncer announcer;

    public ConformanceAnnouncementStartup(ConformanceAnnouncer announcer) {
        this.announcer = announcer;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            announcer.announceIfChanged();
        } catch (RuntimeException e) {
            // A failed announcement must not stop the service starting. Replicas keep the vectors
            // they hold, which is the previous known-good set, and the next restart tries again.
            log.warn("Could not announce the conformance vector set; replicas keep their current set.", e);
        }
    }
}
