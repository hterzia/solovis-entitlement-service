package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.store.SnapshotVersionRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;

/**
 * The one place a write path advances the model. Must be called from inside a {@code @Transactional}
 * method, as the last step, after the row-level mutation and its audit_event are already written on
 * the same connection (admin-api.md, "Write semantics common to every mutating route"; c30). The
 * snapshot_version row commits with everything else; reads answer directly from SQLite, so there is
 * no in-memory swap to defer. The returned version is the row's autoincrement key.
 */
@Component
public class SnapshotPublisher {

    private final SnapshotVersionRepository snapshotVersionRepository;
    private final Clock clock;

    public SnapshotPublisher(SnapshotVersionRepository snapshotVersionRepository, Clock clock) {
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.clock = clock;
    }

    public long publish(long lastAuditSeq, DeltaChange delta) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "SnapshotPublisher.publish must be called from within an active transaction.");
        }
        String deltaJson = DeltaJson.write(delta);
        return snapshotVersionRepository.insert(new SnapshotVersionRow(
            null, clock.instant().toString(), lastAuditSeq, deltaJson));
    }
}
