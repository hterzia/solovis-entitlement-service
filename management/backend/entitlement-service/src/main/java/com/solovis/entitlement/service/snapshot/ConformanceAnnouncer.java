package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.ServiceStateRepository;
import com.solovis.entitlement.service.time.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Publishes a {@code conformance.changed} delta when this build's conformance vector set differs
 * from the one already announced to replicas (snapshot-feed.md, "Change kinds").
 *
 * <p>Why an announcement is needed at all: a delta-derived replica inherits its predecessor's
 * vectors, and only a full resync fetches a new set. So a replica that stays up across a service
 * redeploy keeps the vectors it started with, and never re-runs its gate against a tightened set —
 * which is exactly the case where a newly added vector would have caught it. The
 * {@code resolverContract} integer already guards a change to the <em>rule</em>; this guards a
 * change to the <em>checks on the rule</em>, which is a different event and deliberately does not
 * bump that integer.
 *
 * <p>Two cases are deliberately silent:
 * <ul>
 *   <li><b>No digest recorded yet</b> — a fresh database. Every replica must full-fetch before it
 *       can serve anything, and a full fetch always carries the current vectors, so there is nobody
 *       to announce to.</li>
 *   <li><b>No audit events yet</b> — a {@code snapshot_version} row must reference one, and nothing
 *       has happened to reference. The same reasoning applies: no replica can be mid-stream on a
 *       database where no change has ever been published.</li>
 * </ul>
 */
@Component
public class ConformanceAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(ConformanceAnnouncer.class);

    private final ConformanceVectorSet vectorSet;
    private final ServiceStateRepository serviceStateRepository;
    private final AuditEventRepository auditEventRepository;
    private final SnapshotPublisher snapshotPublisher;
    private final Clock clock;

    public ConformanceAnnouncer(ConformanceVectorSet vectorSet, ServiceStateRepository serviceStateRepository,
            AuditEventRepository auditEventRepository, SnapshotPublisher snapshotPublisher, Clock clock) {
        this.vectorSet = vectorSet;
        this.serviceStateRepository = serviceStateRepository;
        this.auditEventRepository = auditEventRepository;
        this.snapshotPublisher = snapshotPublisher;
        this.clock = clock;
    }

    /**
     * @return the version published, or empty when there was nothing to announce
     */
    @Transactional
    public Optional<Long> announceIfChanged() {
        String digest = vectorSet.digest();
        Optional<String> announced = serviceStateRepository.find(ServiceStateRepository.CONFORMANCE_DIGEST);
        String now = Timestamps.iso(clock.instant());

        if (announced.isEmpty()) {
            serviceStateRepository.put(ServiceStateRepository.CONFORMANCE_DIGEST, digest, now);
            return Optional.empty();
        }
        if (announced.get().equals(digest)) {
            return Optional.empty();
        }

        Optional<Long> lastAuditSeq = auditEventRepository.findMaxSeq();
        if (lastAuditSeq.isEmpty()) {
            serviceStateRepository.put(ServiceStateRepository.CONFORMANCE_DIGEST, digest, now);
            return Optional.empty();
        }

        // Identity mutation: the model is untouched, only the version advances, because a delta is
        // only transportable as a snapshot_version row. Replicas apply it by replacing their vector
        // set and re-gating; no decision changes.
        long version = snapshotPublisher.publish(
            (base, newVersion) -> SnapshotMutator.withVersion(base, newVersion),
            lastAuditSeq.get(),
            new DeltaChange.ConformanceChanged(vectorSet.projected()));

        serviceStateRepository.put(ServiceStateRepository.CONFORMANCE_DIGEST, digest, now);
        log.info("Conformance vector set changed; announced as snapshot version {} for replicas to re-gate.", version);
        return Optional.of(version);
    }
}
