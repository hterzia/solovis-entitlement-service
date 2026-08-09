package com.solovis.entitlement.service.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void unknownAccountMapsToProblemJson() throws Exception {
        mockMvc.perform(get("/test/unknown-account"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/unknown-account"))
            .andExpect(jsonPath("$.account").value("acct_missing"));
    }

    @Test
    void apiExceptionMapsToItsDeclaredStatusAndSlug() throws Exception {
        mockMvc.perform(get("/test/reason-required"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/reason-required"));
    }
}
