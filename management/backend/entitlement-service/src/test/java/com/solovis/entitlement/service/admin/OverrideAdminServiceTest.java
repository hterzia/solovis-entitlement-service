package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
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
}
