package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CapabilityAdminServiceTest {

    @Autowired CapabilityAdminService service;
    @Autowired SnapshotHolder snapshotHolder;

    @Test
    void createPublishesTheCapabilityIntoTheLiveSnapshot() {
        var request = new CapabilityCreateRequest("reports.monthly", "Monthly reports", "desc", "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null);

        var created = service.create(request);

        assertThat(created.key()).isEqualTo("reports.monthly");
        assertThat(snapshotHolder.current().capability(new com.solovis.entitlement.core.model.CapabilityKey("reports.monthly")))
            .isPresent();
    }

    @Test
    void createRejectsDuplicateKey() {
        var request = new CapabilityCreateRequest("api.access", "API", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null);
        service.create(request);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createRejectsDefaultTypeMismatch() {
        var request = new CapabilityCreateRequest("seats.count", "Seats", null, "QUANTITY",
            new ValueDto("SWITCH", true, null, null, null, null), null, null);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALUE_TYPE_MISMATCH);
    }

    @Test
    void createRejectsFewerThanTwoTiers() {
        var request = new CapabilityCreateRequest("support.tier", "Support", null, "TIER",
            new ValueDto("TIER", null, null, null, "community", null), null,
            List.of(new CapabilityCreateRequest.TierRequest("community", "Community")));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void appendTierAddsAboveTheCurrentMaximumOrdinal() {
        var create = new CapabilityCreateRequest("support.level", "Support", null, "TIER",
            new ValueDto("TIER", null, null, null, "community", null), null,
            List.of(new CapabilityCreateRequest.TierRequest("community", "Community"),
                    new CapabilityCreateRequest.TierRequest("gold", "Gold")));
        service.create(create);

        var updated = service.appendTier("support.level", new com.solovis.entitlement.service.admin.dto.TierAppendRequest("platinum", "Platinum"));

        assertThat(updated.tiers()).extracting(t -> t.tier()).containsExactly("community", "gold", "platinum");
        assertThat(updated.tiers().get(2).ordinal()).isEqualTo(2);
    }

    @Test
    void retireReturnsUsageAndRemainsReadableAfterwards() {
        var create = new CapabilityCreateRequest("export.parquet", "Export", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null);
        service.create(create);

        var result = service.retire("export.parquet");

        assertThat(result.capability().status()).isEqualTo("RETIRED");
        assertThat(result.usage().plans()).isEmpty();
        assertThat(result.usage().liveOverrides()).isZero();
    }
}
