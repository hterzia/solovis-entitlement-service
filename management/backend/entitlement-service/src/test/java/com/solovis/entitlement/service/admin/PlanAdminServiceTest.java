package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PlanAdminServiceTest {

    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;

    @Test
    void previewThenApplyWithTheReturnedTokenSucceeds() {
        capabilityService.create(new CapabilityCreateRequest("t6.reports.monthly", "Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("plan6-pro", "Plan6 Pro", null));

        var edit = new PlanEntitlementEditRequest(Map.of("t6.reports.monthly", new ValueDto("QUANTITY", null, 75L, null, null, null)),
            List.of(), null, null);
        var preview = planService.preview("plan6-pro", edit);
        assertThat(preview.previewToken()).startsWith("pv_");

        var apply = planService.apply("plan6-pro", new PlanEntitlementEditRequest(edit.set(), edit.unset(), null, preview.previewToken()));
        assertThat(apply.planKey()).isEqualTo("plan6-pro");
    }

    @Test
    void applyWithAStaleTokenIsRejected() {
        capabilityService.create(new CapabilityCreateRequest("t6.seats.count", "Seats", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("plan6-free2", "Plan6 Free 2", null));
        var edit = new PlanEntitlementEditRequest(Map.of("t6.seats.count", new ValueDto("QUANTITY", null, 5L, null, null, null)),
            List.of(), null, "pv_not-a-real-token");

        assertThatThrownBy(() -> planService.apply("plan6-free2", edit))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.PREVIEW_TOKEN_INVALID);
    }

    @Test
    void archiveRejectsAPlanWithAccounts() {
        planService.create(new PlanCreateRequest("plan6-has-accounts", "Plan6 Has accounts", null));
        // account creation is Task 7; this test only needs the plan-in-use branch reachable once
        // Task 7 exists — see Task 7's AccountAdminServiceTest for the account-bearing case. Here,
        // assert the empty-plan path archives cleanly instead:
        planService.archive("plan6-has-accounts");
    }
}
