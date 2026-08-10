package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DeltaFeedServiceTest {

    @Autowired DeltaFeedService deltaFeedService;
    @Autowired SnapshotPublisher snapshotPublisher;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;
    @Autowired PlatformTransactionManager entitlementTransactionManager;

    private long currentVersion() {
        return snapshotVersionRepository.findLatest().map(row -> row.version()).orElse(0L);
    }

    @Test
    void sinceEqualsCurrentReturnsEmptyChanges() {
        long current = currentVersion();
        var result = deltaFeedService.since(current);
        assertThat(result.changes()).isEmpty();
        assertThat(result.fromVersion()).isEqualTo(current);
        assertThat(result.toVersion()).isEqualTo(current);
    }

    @Test
    void sinceGreaterThanCurrentIsRejected() {
        long current = currentVersion();
        assertThatThrownByGreaterThanCurrent(current);
    }

    private void assertThatThrownByGreaterThanCurrent(long current) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deltaFeedService.since(current + 1000))
            .isInstanceOf(com.solovis.entitlement.service.error.EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALIDATION_FAILED);
    }
}
