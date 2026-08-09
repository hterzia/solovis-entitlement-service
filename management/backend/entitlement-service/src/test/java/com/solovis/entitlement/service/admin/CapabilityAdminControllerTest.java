package com.solovis.entitlement.service.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
