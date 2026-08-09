package com.solovis.entitlement.service.admin;

import com.jayway.jsonpath.JsonPath;
import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanPatchRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    void listByAccountIncludesTheAccountsOwnCreationEventWithItsDefaultPlanKey() throws Exception {
        planService.create(new PlanCreateRequest("tacct.audit-default-plan", "Tacct Audit Default Plan", null));
        planService.designateDefault("tacct.audit-default-plan");

        accountService.create(new AccountCreateRequest("acct_tacct_2", null));

        mockMvc.perform(get("/admin/v1/audit").param("account", "acct_tacct_2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events[?(@.entityType=='ACCOUNT' && @.action=='CREATE')].after.planKey")
                .value(org.hamcrest.Matchers.hasItem("tacct.audit-default-plan")));
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

    @Test
    void listWithAMalformedCursorIsRejectedAsValidationFailedNotA500() throws Exception {
        mockMvc.perform(get("/admin/v1/audit").param("cursor", "xyz"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }

    @Test
    void listByPlanKeyFiltersToJustThatPlan() throws Exception {
        planService.create(new PlanCreateRequest("t29a.plan", "T29a Plan", null));
        planService.create(new PlanCreateRequest("t29b.plan", "T29b Plan", null));
        // Produces a second, PLAN/UPDATE audit event scoped to t29a.plan, alongside the
        // PLAN/CREATE event from create() above.
        planService.patch("t29a.plan", new PlanPatchRequest("T29a Plan Renamed", null));

        mockMvc.perform(get("/admin/v1/audit").param("planKey", "t29a.plan"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events[*].planKey", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("t29a.plan"))))
            .andExpect(jsonPath("$.events[?(@.entityType=='PLAN' && @.action=='UPDATE')].planKey")
                .value(org.hamcrest.Matchers.hasItem("t29a.plan")));
    }

    @Test
    void cursorPagingWalksStrictlyDescendingSeqsWithNoRepeats() throws Exception {
        capabilityService.create(new CapabilityCreateRequest("t29.cursor.one", "Cursor probe one", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        capabilityService.create(new CapabilityCreateRequest("t29.cursor.two", "Cursor probe two", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        capabilityService.create(new CapabilityCreateRequest("t29.cursor.three", "Cursor probe three", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));

        String page1Body = mockMvc.perform(get("/admin/v1/audit").param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(1))
            .andReturn().getResponse().getContentAsString();
        long seq1 = ((Number) JsonPath.read(page1Body, "$.events[0].seq")).longValue();
        String cursor1 = JsonPath.read(page1Body, "$.nextCursor");

        String page2Body = mockMvc.perform(get("/admin/v1/audit").param("limit", "1").param("cursor", cursor1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(1))
            .andReturn().getResponse().getContentAsString();
        long seq2 = ((Number) JsonPath.read(page2Body, "$.events[0].seq")).longValue();
        String cursor2 = JsonPath.read(page2Body, "$.nextCursor");

        String page3Body = mockMvc.perform(get("/admin/v1/audit").param("limit", "1").param("cursor", cursor2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(1))
            .andReturn().getResponse().getContentAsString();
        long seq3 = ((Number) JsonPath.read(page3Body, "$.events[0].seq")).longValue();

        assertThat(seq1).isGreaterThan(seq2);
        assertThat(seq2).isGreaterThan(seq3);
    }
}
