package com.solovis.entitlement.service.snapshot;

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
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired PlatformTransactionManager entitlementTransactionManager;

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

    private void assertThatThrownByGreaterThanCurrent(long current) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deltaFeedService.since(current + 1000))
            .isInstanceOf(com.solovis.entitlement.service.error.EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALIDATION_FAILED);
    }
}
