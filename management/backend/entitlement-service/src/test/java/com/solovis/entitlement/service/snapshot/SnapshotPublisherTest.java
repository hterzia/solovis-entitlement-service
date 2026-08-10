package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.AuditEventRow;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// snapshot_version.last_audit_seq carries a hard FK to audit_event(seq) (V1__baseline.sql) — every
// publish() call here must reference a real row, so each test seeds one via AuditEventRepository
// rather than passing an arbitrary literal.
@SpringBootTest
class SnapshotPublisherTest {

    @Autowired SnapshotPublisher publisher;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;
    @Autowired PlatformTransactionManager entitlementTransactionManager;
    @Autowired AuditEventRepository auditEventRepository;

    private long seedAuditEvent() {
        return auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "SYSTEM",
            "dev-operator", "API", "CAPABILITY", "api.access", "CREATE",
            null, null, null, null, null, null, null));
    }

    @Test
    void publishReturnsTheAutoincrementVersion() {
        long auditSeq1 = seedAuditEvent();
        long auditSeq2 = seedAuditEvent();

        long[] versions = new long[2];
        new TransactionTemplate(entitlementTransactionManager).executeWithoutResult(status -> {
            versions[0] = publisher.publish(auditSeq1, new DeltaChange.PlanArchived("does-not-exist-1"));
            versions[1] = publisher.publish(auditSeq2, new DeltaChange.PlanArchived("does-not-exist-2"));
        });

        assertThat(versions[1]).isEqualTo(versions[0] + 1);
        assertThat(snapshotVersionRepository.findByVersion(versions[0])).isPresent();
        assertThat(snapshotVersionRepository.findByVersion(versions[1])).isPresent();
    }

    @Test
    void rollbackLeavesNoRow() {
        long auditSeq = seedAuditEvent();
        long latestBefore = snapshotVersionRepository.findLatest().map(row -> row.version()).orElse(0L);

        new TransactionTemplate(entitlementTransactionManager).executeWithoutResult(status -> {
            publisher.publish(auditSeq, new DeltaChange.PlanArchived("does-not-exist"));
            status.setRollbackOnly();
        });

        assertThat(snapshotVersionRepository.findLatest().map(row -> row.version()).orElse(0L)).isEqualTo(latestBefore);
    }

    @Test
    void throwsOutsideATransactionBeforeWritingAnything() {
        long auditSeq = seedAuditEvent();
        long latestBefore = snapshotVersionRepository.findLatest().map(row -> row.version()).orElse(0L);

        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> publisher.publish(auditSeq, new DeltaChange.PlanArchived("does-not-exist")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(snapshotVersionRepository.findLatest().map(row -> row.version()).orElse(0L)).isEqualTo(latestBefore);
    }
}
