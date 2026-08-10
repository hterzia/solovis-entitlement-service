package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CheckerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired AccountAdminService accountService;
    @Autowired OverrideAdminService overrideService;

    /**
     * c38 — the checker answers for any account and capability, and c24 — byte for byte the same
     * payload the evaluation interface returns, because it is the same call and not a second
     * implementation.
     */
    @Test
    void checkByAccountAndCapabilityMatchesTheDecisionApiPayload() throws Exception {
        planService.create(new PlanCreateRequest("t8-check-plan", "Check plan", null));
        planService.designateDefault("t8-check-plan");
        capabilityService.create(new CapabilityCreateRequest("t8.api.access", "API", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("t8-acct-check", null));

        mockMvc.perform(get("/admin/v1/check").param("account", "t8-acct-check").param("capability", "t8.api.access"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trace.baseline.source").value("CAPABILITY_DEFAULT"));
    }

    @Test
    void checkByOverrideResolvesTheOwningAccountAndCapability() throws Exception {
        planService.create(new PlanCreateRequest("t8-check-ovr-plan", "Check override plan", null));
        planService.designateDefault("t8-check-ovr-plan");
        capabilityService.create(new CapabilityCreateRequest("t8.grant.only", "Grant only", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("t8-acct-override", null));

        var created = overrideService.create("t8-acct-override", new OverrideCreateRequest("t8.grant.only", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "t8 override test"));

        mockMvc.perform(get("/admin/v1/check").param("override", created.overrideId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trace.baseline.source").value("CAPABILITY_DEFAULT"));
    }

    @Test
    void checkWithAMalformedOverrideRefIsRejectedAsValidationFailedNotA500() throws Exception {
        mockMvc.perform(get("/admin/v1/check").param("override", "nope"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }

    @Test
    void checkResponseIsNeverBrowserCachedSoAnOperatorSeesTheirOwnSaveImmediately() throws Exception {
        planService.create(new PlanCreateRequest("t8-check-nostore-plan", "Check no-store plan", null));
        planService.designateDefault("t8-check-nostore-plan");
        capabilityService.create(new CapabilityCreateRequest("t8.nostore.access", "API", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("t8-acct-nostore", null));

        mockMvc.perform(get("/admin/v1/check").param("account", "t8-acct-nostore").param("capability", "t8.nostore.access"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("X-Entitlement-Snapshot-Version"));
    }
}
