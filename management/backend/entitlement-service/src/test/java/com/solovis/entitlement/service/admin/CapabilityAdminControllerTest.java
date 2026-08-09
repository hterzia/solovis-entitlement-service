package com.solovis.entitlement.service.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
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
}
