package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.audit.AuditSource;
import com.solovis.entitlement.service.store.ServiceStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * Writes the demo dataset into an empty database, before the service accepts traffic.
 *
 * <p>An {@link InitializingBean} rather than an {@code ApplicationRunner}, for two reasons. Boot
 * starts the web connector during context refresh, before any runner fires, so a runner would leave
 * a window where the console is reachable and half populated. And {@code @Scheduled} tasks start
 * with the context lifecycle, also after every {@code InitializingBean} — which is what keeps
 * {@code WindowBoundaryRoller} (fixed delay, {@code initialDelay = 0}, reads
 * {@code LocalDate.now(clock)}) from ever observing the wound clock. As a runner it would fire
 * mid-seed, take an authored day for today, record {@code window.rolledThrough} in the fictional
 * past, and then publish a flood of boundary transitions for moments nobody observed.
 *
 * <p>It needs no {@code @DependsOn}: {@code SnapshotPublisher} derives its version from the
 * {@code snapshot_version} autoincrement rather than from anything held in memory, so a migrated
 * schema is the only precondition and context refresh already guarantees it. This mirrors
 * {@code ConformanceAnnouncementStartup}, which states the same reasoning.
 *
 * <p>Whether a database has been seeded is recorded, not inferred. The old check asked whether any
 * plan existed — one question standing in for the whole sequence, so a crash partway through left a
 * permanently half-populated demo that every later boot skipped in silence. A started-but-never-
 * completed marker now fails startup instead, which for a demo database is the better failure: the
 * history must be written in time order into an empty database, so the recovery is to delete the
 * file rather than to patch up what is there.
 */
@Component
@ConditionalOnProperty(name = "entitlement.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String RESOURCE = "seed/demo-seed.json";

    private final SeedApplier applier;
    private final SeedState seedState;
    private final SeedClock clock;

    public DemoDataSeeder(CapabilityAdminService capabilityService, PlanAdminService planService,
            AccountAdminService accountService, OverrideAdminService overrideService, AuditSource auditSource,
            ServiceStateRepository serviceStateRepository, SeedClock clock) {
        this.applier = new SeedApplier(capabilityService, planService, accountService, overrideService,
            auditSource, clock);
        this.seedState = new SeedState(serviceStateRepository, clock);
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        SeedState.Status status = seedState.status();
        if (status == SeedState.Status.COMPLETED) {
            log.info("Demo seed: already present, nothing to do.");
            return;
        }
        if (status == SeedState.Status.STARTED) {
            throw new IllegalStateException("Demo seed: a previous attempt ("
                + seedState.startedFingerprint().orElse("unknown") + ") started but never completed, so this "
                + "database is half populated. Refusing to start rather than serve an incomplete demo — "
                + "delete the database file and restart.");
        }

        SeedDataset dataset = load();
        dataset.validate();

        long began = System.nanoTime();
        try {
            seedState.markStarted(dataset.fingerprint());
            SeedApplier.Summary summary = applier.apply(dataset);
            seedState.markCompleted(dataset.fingerprint());
            log.info("Demo seed: {} capabilities, {} plans, {} accounts, {} overrides — {} writes spanning {} "
                    + "days, in {} ms.", summary.capabilities(), summary.plans(), summary.accounts(),
                summary.overrides(), summary.writes(),
                Duration.between(summary.firstEvent(), summary.lastEvent()).toDays(),
                Duration.ofNanos(System.nanoTime() - began).toMillis());
        } finally {
            // Released before the connector opens and before @Scheduled tasks start: no request and
            // no roll ever observes a wound clock.
            clock.release();
        }
    }

    private SeedDataset load() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return SeedDataset.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Demo seed: " + RESOURCE + " is not readable", e);
        }
    }
}
