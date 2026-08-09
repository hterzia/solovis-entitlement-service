package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.AuditEventRow;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// snapshot_version.last_audit_seq carries a hard FK to audit_event(seq) (V1__baseline.sql) — every
// publish() call here must reference a real row, so each test seeds one via AuditEventRepository
// rather than passing an arbitrary literal as the brief's illustrative code did.
//
// Not @Transactional, same reasoning as CapabilityAdminServiceTest: the manual TransactionTemplate
// commit below needs a real commit for SnapshotPublisher's afterCommit() swap to fire at all. But
// that means holder.set(seed) and the synthetic "api.access" Capability this test publishes — which
// is never backed by an actual capability table row — become permanent state in the shared
// SnapshotHolder singleton (reused across every @SpringBootTest class in this JVM fork, per
// SnapshotHolder's javadoc). Left alone, any later non-transactional admin-service test that iterates
// snapshot.activeCapabilities() and looks each one up by key hits a phantom "api.access" with no row
// to find. Restore the holder to the real, DB-backed snapshot afterward, same as
// PlanAdminControllerTest's default-plan cleanup.
@SpringBootTest
class SnapshotPublisherTest {

    @Autowired SnapshotPublisher publisher;
    @Autowired SnapshotHolder holder;
    @Autowired SnapshotAssembler assembler;
    @Autowired PlatformTransactionManager entitlementTransactionManager;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;

    @AfterEach
    void restoreHolderFromDatabase() {
        holder.set(assembler.assembleFull());
    }

    private long seedAuditEvent() {
        return auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "SYSTEM",
            "dev-operator", "API", "CAPABILITY", "api.access", "CREATE",
            null, null, null, null, null, null, null));
    }

    @Test
    void swapHappensOnlyAfterCommitNotDuringTheTransaction() {
        var seed = new SnapshotBuilder().build(0);
        holder.set(seed);
        var newCapability = new Capability(new CapabilityKey("api.access"), "API", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        long auditSeq = seedAuditEvent();

        new TransactionTemplate(entitlementTransactionManager).executeWithoutResult(status -> {
            long version = publisher.publish(
                (base, v) -> com.solovis.entitlement.core.view.SnapshotMutator.withCapability(base, v, newCapability),
                auditSeq, new DeltaChange.CapabilityUpserted(com.solovis.entitlement.service.dto.CapabilityDescriptorMapper.toDescriptor(newCapability)));
            assertThat(version).isEqualTo(1L);
            // still inside the transaction: the holder must not have swapped yet
            assertThat(holder.current().capability(new CapabilityKey("api.access"))).isEmpty();
        });

        // transaction has committed: the swap has now happened
        assertThat(holder.current().capability(new CapabilityKey("api.access"))).isPresent();
        assertThat(holder.current().snapshotVersion()).isEqualTo(1L);
    }

    @Test
    void publishOutsideATransactionThrowsAndWritesNothing() {
        var seed = new SnapshotBuilder().build(0);
        holder.set(seed);
        long auditSeq = seedAuditEvent();
        int rowsBefore = snapshotVersionRepository.findSince(0, Integer.MAX_VALUE).size();

        // No TransactionTemplate here, and this test class is not @Transactional: publish() is
        // invoked with no active transaction, which is exactly the case the guard exists for.
        assertThatThrownBy(() -> publisher.publish((base, v) -> base, auditSeq,
                new DeltaChange.PlanArchived("does-not-exist")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active transaction");

        int rowsAfter = snapshotVersionRepository.findSince(0, Integer.MAX_VALUE).size();
        assertThat(rowsAfter).isEqualTo(rowsBefore);
        assertThat(holder.current().snapshotVersion()).isEqualTo(0L);
    }

    @Test
    void rollbackNeverSwapsTheHolder() {
        var seed = new SnapshotBuilder().build(0);
        holder.set(seed);
        long auditSeq = seedAuditEvent();

        try {
            new TransactionTemplate(entitlementTransactionManager).executeWithoutResult(status -> {
                publisher.publish((base, v) -> base, auditSeq, new DeltaChange.PlanArchived("does-not-exist"));
                status.setRollbackOnly();
            });
        } catch (Exception ignored) { /* rollback path only, no assertion on the exception itself */ }

        assertThat(holder.current().snapshotVersion()).isEqualTo(0L);
    }
}
