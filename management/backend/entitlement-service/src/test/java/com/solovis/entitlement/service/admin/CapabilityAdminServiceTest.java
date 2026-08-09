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

/**
 * Deliberately not {@code @Transactional}: {@link CapabilityAdminService#create} and
 * {@code #patch} publish into {@link SnapshotHolder} from a {@code TransactionSynchronization
 * .afterCommit()} callback (see SnapshotPublisher), which never fires if the whole test method
 * runs inside an outer test-managed transaction that always rolls back. Its writes also can't be
 * cleaned up after the fact: every created capability gets an audit_event row referencing it, and
 * audit_event is append-only (BEFORE DELETE/UPDATE triggers RAISE(ABORT) — see V1__baseline.sql),
 * so the capability row can never be deleted afterward either. This class's commits are permanent
 * for the life of the test JVM, same as SnapshotPublisherTest's manual-transaction case.
 */
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
        var request = new CapabilityCreateRequest("billing.seat-count", "Seats", null, "QUANTITY",
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
    void createRejectsEmptyTierList() {
        var request = new CapabilityCreateRequest("support.plan", "Support", null, "TIER",
            new ValueDto("TIER", null, null, null, "community", null), null, List.of());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void patchUpdatesDisplayNameAndDescriptionLeavingValuesUnchanged() {
        var create = new CapabilityCreateRequest("billing.invoices", "Invoices", "old desc", "QUANTITY",
            new ValueDto("QUANTITY", null, 10L, null, null, null), null, null);
        service.create(create);

        var patch = new com.solovis.entitlement.service.admin.dto.CapabilityPatchRequest("Invoices v2", "new desc", null, null);
        var updated = service.patch("billing.invoices", patch);

        assertThat(updated.displayName()).isEqualTo("Invoices v2");
        assertThat(updated.description()).isEqualTo("new desc");
        assertThat(updated.defaultValue().amount()).isEqualTo(10L);
        assertThat(service.get("billing.invoices").displayName()).isEqualTo("Invoices v2");
        assertThat(snapshotHolder.current()
            .capability(new com.solovis.entitlement.core.model.CapabilityKey("billing.invoices"))
            .orElseThrow().displayName()).isEqualTo("Invoices v2");
    }

    @Test
    void patchUpdatesDefaultAndOffValueThroughValueMapper() {
        var create = new CapabilityCreateRequest("billing.seats", "Seats", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 10L, null, null, null), null, null);
        service.create(create);

        var patch = new com.solovis.entitlement.service.admin.dto.CapabilityPatchRequest(null, null,
            new ValueDto("QUANTITY", null, 50L, null, null, null), new ValueDto("QUANTITY", null, 0L, null, null, null));
        var updated = service.patch("billing.seats", patch);

        assertThat(updated.defaultValue().amount()).isEqualTo(50L);
        assertThat(updated.offValue().amount()).isEqualTo(0L);
    }

    @Test
    void getReturnsWhatCreateReturned() {
        var create = new CapabilityCreateRequest("billing.plans", "Plans", null, "SWITCH",
            new ValueDto("SWITCH", true, null, null, null, null), null, null);
        var created = service.create(create);

        assertThat(service.get("billing.plans")).isEqualTo(created);
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
