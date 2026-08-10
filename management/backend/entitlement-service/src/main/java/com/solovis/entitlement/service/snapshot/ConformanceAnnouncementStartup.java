package com.solovis.entitlement.service.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ConformanceAnnouncer} once at startup, which is the only moment the compiled vector
 * set can have changed.
 *
 * <p>Takes {@link SnapshotStartup} as a constructor argument purely for ordering: the announcement
 * publishes a new snapshot version, and {@link SnapshotPublisher} reads the held snapshot to derive
 * it, so the holder must already be populated. Expressing that as a dependency is what makes the
 * ordering a fact of the bean graph rather than a hope about initialisation order.
 *
 * <p>An {@code InitializingBean} rather than an {@code ApplicationRunner} for the same reason
 * {@link SnapshotStartup} is: runners fire after the web connector is already accepting traffic.
 * Nothing breaks if a replica polls in that window — it would simply see the announcement one poll
 * later — but there is no reason to leave the window open.
 */
@Component
public class ConformanceAnnouncementStartup implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ConformanceAnnouncementStartup.class);

    private final ConformanceAnnouncer announcer;

    public ConformanceAnnouncementStartup(ConformanceAnnouncer announcer, SnapshotStartup snapshotStartup) {
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
