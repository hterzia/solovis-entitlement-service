package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DeltaFeedServiceTest {

    @Autowired DeltaFeedService deltaFeedService;
    @Autowired SnapshotPublisher snapshotPublisher;
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired PlatformTransactionManager entitlementTransactionManager;
    @Autowired CapabilityAdminService capabilityAdminService;
    @Autowired @Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient;

    @Test
    void sinceEqualsCurrentReturnsEmptyChanges() {
        long current = snapshotHolder.current().snapshotVersion();
        var result = deltaFeedService.since(current);
        assertThat(result.changes()).isEmpty();
        assertThat(result.fromVersion()).isEqualTo(current);
        assertThat(result.toVersion()).isEqualTo(current);
    }

    @Test
    void sinceGreaterThanCurrentIsRejected() {
        long current = snapshotHolder.current().snapshotVersion();
        assertThatThrownByGreaterThanCurrent(current);
    }

    @Test
    void contiguousSinceReturnsChangesStartingAtSincePlusOne() {
        long start = snapshotHolder.current().snapshotVersion();
        createCapability();
        createCapability();
        long current = snapshotHolder.current().snapshotVersion();
        assertThat(current).isEqualTo(start + 2);

        var result = deltaFeedService.since(start);

        assertThat(result.fromVersion()).isEqualTo(start);
        assertThat(result.toVersion()).isEqualTo(current);
        assertThat(result.changes()).hasSize(2);
        assertThat(result.changes().get(0).version()).isEqualTo(start + 1);
        assertThat(result.changes().get(1).version()).isEqualTo(start + 2);
    }

    @Test
    void sinceOlderThanRetainedHorizonFailsSafeWithSnapshotTooOld() {
        long start = snapshotHolder.current().snapshotVersion();
        createCapability();
        long prunedVersion = start + 1;
        createCapability();
        createCapability();
        long current = snapshotHolder.current().snapshotVersion();
        assertThat(current).isEqualTo(start + 3);

        // Simulate the retention horizon pruning the row immediately after `start` — there is no
        // append-only trigger on snapshot_version (unlike audit_event), so a raw DELETE stands in
        // for whatever background job actually prunes rows older than the retained horizon.
        int deleted = jdbcClient.sql("DELETE FROM snapshot_version WHERE version = :version")
            .param("version", prunedVersion)
            .update();
        assertThat(deleted).isEqualTo(1);

        assertThatThrownBy(() -> deltaFeedService.since(start))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.SNAPSHOT_TOO_OLD);

        assertThatThrownBy(() -> deltaFeedService.since(start))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(EntitlementApiException.class))
            .extracting(EntitlementApiException::extraProperties)
            .satisfies(extra -> assertThat(extra).containsEntry("currentVersion", current));
    }

    private void createCapability() {
        capabilityAdminService.create(new CapabilityCreateRequest(
            "t9c.delta-feed-service." + UUID.randomUUID(), "Delta feed probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
    }

    private void assertThatThrownByGreaterThanCurrent(long current) {
        assertThatThrownBy(() -> deltaFeedService.since(current + 1000))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
