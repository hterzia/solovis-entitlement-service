package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OverrideAdminServiceTest {

    @Autowired OverrideAdminService overrideService;
    @Autowired AccountAdminService accountService;
    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountOverrideRepository accountOverrideRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;

    @Test
    void createRejectsAnEmptyReason() {
        planService.create(new PlanCreateRequest("pro4", "Pro 4", null));
        planService.designateDefault("pro4");
        capabilityService.create(new CapabilityCreateRequest("t7.seats.count", "Seats", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_reason", null));

        assertThatThrownBy(() -> overrideService.create("acct_reason", new OverrideCreateRequest("t7.seats.count", "GRANT",
            new ValueDto("QUANTITY", null, 100L, null, null, null), "  ")))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.REASON_REQUIRED);
    }

    @Test
    void createThenDeleteRestoresTheUnderlyingValue() {
        planService.create(new PlanCreateRequest("pro5", "Pro 5", null));
        planService.designateDefault("pro5");
        capabilityService.create(new CapabilityCreateRequest("t7.api.access", "API", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_override", null));

        var created = overrideService.create("acct_override", new OverrideCreateRequest("t7.api.access", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "trial access"));
        assertThat(created.decision().allowed()).isTrue();

        var afterDelete = overrideService.delete("acct_override", created.overrideId(), null);
        assertThat(afterDelete.decision().allowed()).isFalse();
    }

    @Test
    void deleteRejectsAMalformedOverrideRefInsteadOfThrowingUnhandled() {
        planService.create(new PlanCreateRequest("t7-malformed-ref-plan", "T7 Malformed Ref Plan", null));
        planService.designateDefault("t7-malformed-ref-plan");
        accountService.create(new AccountCreateRequest("acct_malformed_ref", null));

        assertThatThrownBy(() -> overrideService.delete("acct_malformed_ref", "ovr_not-a-number", null))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void deleteRejectsAnOverrideThatBelongsToAnotherAccount() {
        planService.create(new PlanCreateRequest("t7-owner-plan", "T7 Owner Plan", null));
        planService.designateDefault("t7-owner-plan");
        capabilityService.create(new CapabilityCreateRequest("t7.owner.check", "Owner check", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_owner_a", null));
        accountService.create(new AccountCreateRequest("acct_owner_b", null));

        var created = overrideService.create("acct_owner_a", new OverrideCreateRequest("t7.owner.check", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "belongs to account A only"));

        assertThatThrownBy(() -> overrideService.delete("acct_owner_b", created.overrideId(), null))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);

        var stillLive = accountService.get("acct_owner_a").overrides().stream()
            .anyMatch(o -> o.id().equals(created.overrideId()));
        assertThat(stillLive).isTrue();
    }

    @Test
    void deleteAnOverrideOnARetiredCapabilitySucceeds() {
        planService.create(new PlanCreateRequest("tovr-retired-plan", "Tovr Retired Plan", null));
        planService.designateDefault("tovr-retired-plan");
        capabilityService.create(new CapabilityCreateRequest("tovr.export.parquet", "Export Parquet", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_tovr_1", null));

        var created = overrideService.create("acct_tovr_1", new OverrideCreateRequest("tovr.export.parquet", "HOLD",
            new ValueDto("SWITCH", false, null, null, null, null), "block export pending review"));

        capabilityService.retire("tovr.export.parquet");

        var response = overrideService.delete("acct_tovr_1", created.overrideId(), "cleanup after retire");

        assertThat(response.decision()).isNull();

        long accountId = accountRepository.findByExternalId("acct_tovr_1").orElseThrow().id();
        var stillLive = accountOverrideRepository.findLiveForAccount(accountId).stream()
            .anyMatch(o -> ("ovr_" + o.id()).equals(created.overrideId()));
        assertThat(stillLive).isFalse();

        var removeEvent = auditEventRepository.find(new AuditEventFilter(accountId, null, null, "OVERRIDE", null, null, null, 50))
            .stream()
            .filter(e -> "REMOVE".equals(e.action()) && created.overrideId().equals(e.entityId()))
            .findFirst();
        assertThat(removeEvent).isPresent();
    }

    @Test
    void deleteRecordsTheCanonicalRefOnTheDeltaFeed() {
        planService.create(new PlanCreateRequest("tovr-canon-plan", "Tovr Canon Plan", null));
        planService.designateDefault("tovr-canon-plan");
        capabilityService.create(new CapabilityCreateRequest("tovr.canon.check", "Canon check", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_tovr_canon", null));

        var created = overrideService.create("acct_tovr_canon", new OverrideCreateRequest("tovr.canon.check", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "canon ref test"));
        overrideService.delete("acct_tovr_canon", created.overrideId(), null);

        var latest = snapshotVersionRepository.findLatest().orElseThrow();
        assertThat(latest.deltaJson()).contains(created.overrideId());
    }

    @Test
    void deleteRecordsWhatWasRemoved() {
        planService.create(new PlanCreateRequest("tovr-before-plan", "Tovr Before Plan", null));
        planService.designateDefault("tovr-before-plan");
        capabilityService.create(new CapabilityCreateRequest("tovr.before.check", "Before check", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_tovr_before", null));

        var created = overrideService.create("acct_tovr_before", new OverrideCreateRequest("tovr.before.check", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "before-payload test reason"));
        overrideService.delete("acct_tovr_before", created.overrideId(), null);

        long accountId = accountRepository.findByExternalId("acct_tovr_before").orElseThrow().id();
        var removeEvent = auditEventRepository.find(new AuditEventFilter(accountId, null, null, "OVERRIDE", null, null, null, 50))
            .stream()
            .filter(e -> "REMOVE".equals(e.action()) && created.overrideId().equals(e.entityId()))
            .findFirst()
            .orElseThrow();

        assertThat(removeEvent.beforeJson()).isNotNull();
        assertThat(removeEvent.beforeJson()).contains("GRANT");
        assertThat(removeEvent.beforeJson()).contains("before-payload test reason");
    }
}
