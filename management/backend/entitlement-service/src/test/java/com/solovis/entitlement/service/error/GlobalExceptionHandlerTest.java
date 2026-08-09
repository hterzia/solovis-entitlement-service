package com.solovis.entitlement.service.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void malformedJsonBodyMapsToValidationFailedProblemJsonNotSpringsDefaultErrorPage() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"value\":"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }

    @Test
    void unexpectedExceptionMapsToAStableInternalErrorProblemJsonRatherThanLeakingAStackTrace() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/internal-error"));
    }

    @Test
    void queryParamTypeMismatchMapsToStableValidationFailedSlugNotAboutBlank() throws Exception {
        mockMvc.perform(get("/test/typed-param").param("value", "not-a-number"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"))
            .andExpect(jsonPath("$.violations").isNotEmpty());
    }

    @Test
    void missingRequiredQueryParamMapsToStableValidationFailedSlugNotAboutBlank() throws Exception {
        mockMvc.perform(get("/test/typed-param"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"))
            .andExpect(jsonPath("$.violations").isNotEmpty());
    }

    @Test
    void wrongHttpMethodMapsTo405NotTheUnexpectedExceptionFallback() throws Exception {
        mockMvc.perform(post("/test/unknown-account"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void dataIntegrityViolationMapsTo409ConflictSlug() throws Exception {
        mockMvc.perform(get("/test/data-integrity-violation"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/conflict"));
    }
}
