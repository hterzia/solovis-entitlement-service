package com.solovis.entitlement.service.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CapabilityAdminControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void createReturns201WithTheDescriptor() throws Exception {
        String body = """
            {"key":"api.access","displayName":"API access","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.key").value("api.access"))
            .andExpect(jsonPath("$.area").value("api"));
    }

    @Test
    void createRejectsMissingDisplayNameWithValidationFailed() throws Exception {
        String body = """
            {"key":"api.access","valueType":"SWITCH","default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }

    @Test
    void getReturnsTheDescriptorFlatWithUsageAlongside() throws Exception {
        String body = """
            {"key":"t9.export.csv","displayName":"Export CSV","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/v1/capabilities/t9.export.csv"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("t9.export.csv"))
            .andExpect(jsonPath("$.displayName").value("Export CSV"))
            .andExpect(jsonPath("$.usage.plans").isArray())
            .andExpect(jsonPath("$.usage.liveOverrides").value(0));
    }

    @Test
    void createRejectsNegativeQuantityAmountWith422AndViolations() throws Exception {
        String body = """
            {"key":"tcap.reports.negative","displayName":"Negative","valueType":"QUANTITY",
             "default":{"type":"QUANTITY","amount":-5}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"))
            .andExpect(jsonPath("$.violations").isNotEmpty());
    }

    @Test
    void createRejectsDefaultValueMissingTypeWith422NotServerError() throws Exception {
        String body = """
            {"key":"tcap.reports.notype","displayName":"No type","valueType":"QUANTITY",
             "default":{"amount":5}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createRejectsMissingDefaultValueWith422() throws Exception {
        String body = """
            {"key":"tcap.reports.nodefault","displayName":"No default","valueType":"QUANTITY"}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void patchAttemptingValueTypeChangeReturns409ImmutableField() throws Exception {
        String createBody = """
            {"key":"tcap.billing.seats","displayName":"Seats","valueType":"QUANTITY",
             "default":{"type":"QUANTITY","amount":10}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated());

        String patchBody = """
            {"valueType":"SWITCH","displayName":"x"}
            """;
        mockMvc.perform(patch("/admin/v1/capabilities/tcap.billing.seats")
                .contentType(MediaType.APPLICATION_JSON).content(patchBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("entitlement/immutable-field"));
    }

    @Test
    void patchWithoutValueTypeStillReturns200() throws Exception {
        String createBody = """
            {"key":"tcap.billing.plan","displayName":"Plan","valueType":"QUANTITY",
             "default":{"type":"QUANTITY","amount":10}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated());

        String patchBody = """
            {"displayName":"Plan v2"}
            """;
        mockMvc.perform(patch("/admin/v1/capabilities/tcap.billing.plan")
                .contentType(MediaType.APPLICATION_JSON).content(patchBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Plan v2"));
    }

    @Test
    void createSameKeyTwiceReturns409OnSecondAttempt() throws Exception {
        String body = """
            {"key":"tcap.export.dup","displayName":"Export","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }

    @Test
    void listGroupedByAreaReturnsAreaGroups() throws Exception {
        String body1 = """
            {"key":"tcap.billing.invoices","displayName":"Invoices","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false}}
            """;
        String body2 = """
            {"key":"tcap.support.tickets","displayName":"Tickets","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body1))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body2))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/v1/capabilities").param("groupBy", "area"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.areas").isNotEmpty())
            .andExpect(jsonPath("$.areas[0].area").exists())
            .andExpect(jsonPath("$.areas[0].capabilities").isArray())
            .andExpect(jsonPath("$.snapshotVersion").exists());
    }

    @Test
    void listWithBogusGroupByReturns422() throws Exception {
        mockMvc.perform(get("/admin/v1/capabilities").param("groupBy", "bogus"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listWithoutGroupByStillReturnsFlatShape() throws Exception {
        mockMvc.perform(get("/admin/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.capabilities").isArray())
            .andExpect(jsonPath("$.snapshotVersion").exists());
    }
}
