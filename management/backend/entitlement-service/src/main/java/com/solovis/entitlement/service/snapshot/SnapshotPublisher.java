package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.store.SnapshotVersionRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import com.solovis.entitlement.service.time.Timestamps;

/**
 * The one place a write path advances the model. Must be called from inside a {@code @Transactional}
 * method, as the last step, after the row-level mutation and its audit_event are already written on
 * the same connection (admin-api.md, "Write semantics common to every mutating route"; c30). The
 * snapshot_version row commits with everything else; the in-memory swap is deferred to
 * {@code afterCommit()} so a rolled-back write can never leave {@link SnapshotHolder} ahead of the
 * database — registerSynchronization throws if no transaction is active, which is the intended
 * guard against calling this outside one.
 */
@Component
public class SnapshotPublisher {

    private final SnapshotVersionRepository snapshotVersionRepository;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public SnapshotPublisher(SnapshotVersionRepository snapshotVersionRepository, SnapshotHolder snapshotHolder, Clock clock) {
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    @FunctionalInterface
    public interface Mutation {
        Snapshot apply(Snapshot base, long newVersion);
    }

    public long publish(Mutation mutation, long lastAuditSeq, DeltaChange delta) {
        Snapshot current = snapshotHolder.current();
        long newVersion = current.snapshotVersion() + 1;
        Snapshot next = mutation.apply(current, newVersion);
        String deltaJson = DeltaJson.write(delta);
        snapshotVersionRepository.insert(new SnapshotVersionRow(null, Timestamps.iso(clock.instant()), lastAuditSeq, deltaJson));

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                snapshotHolder.set(next);
            }
        });
        return newVersion;
    }
}
