package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
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
class AuditControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired PlanAdminService planService;
    @Autowired AccountAdminService accountService;
    @Autowired OverrideAdminService overrideService;

    @Test
    void listByAccountIncludesTheOverrideCreatedForThatAccountWithItsCapabilityKeyResolved() throws Exception {
        planService.create(new PlanCreateRequest("t8-audit-plan", "Audit plan", null));
        planService.designateDefault("t8-audit-plan");
        capabilityService.create(new CapabilityCreateRequest("t8.audit.probe", "Audit probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("t8-acct-audit", null));

        overrideService.create("t8-acct-audit", new OverrideCreateRequest("t8.audit.probe", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "t8 audit probe"));

        mockMvc.perform(get("/admin/v1/audit").param("account", "t8-acct-audit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events[?(@.entityType=='OVERRIDE' && @.account=='t8-acct-audit')].capability")
                .value(org.hamcrest.Matchers.hasItem("t8.audit.probe")))
            .andExpect(jsonPath("$.events[?(@.entityType=='OVERRIDE' && @.account=='t8-acct-audit')].action")
                .value(org.hamcrest.Matchers.hasItem("CREATE")));
    }

    @Test
    void listByEntityTypeFiltersToJustThatEntityType() throws Exception {
        capabilityService.create(new CapabilityCreateRequest("t8.audit.filter-probe", "Audit filter probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));

        mockMvc.perform(get("/admin/v1/audit").param("entityType", "CAPABILITY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events[?(@.entityId=='t8.audit.filter-probe')].action")
                .value(org.hamcrest.Matchers.hasItem("CREATE")))
            .andExpect(jsonPath("$.events[*].entityType", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("CAPABILITY"))));
    }
}
