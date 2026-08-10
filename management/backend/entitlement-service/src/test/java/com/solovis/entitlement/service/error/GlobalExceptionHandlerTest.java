package com.solovis.entitlement.service.error;

import com.solovis.entitlement.service.store.DecisionReadDao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The handler asks {@link DecisionReadDao} for the {@code /v1} snapshot-version header. Every route
 * here lives under {@code /test/**}, where the handler short-circuits before asking — so this slice
 * supplies a DAO that is never consulted rather than standing up a datasource it would never read.
 */
@WebMvcTest(controllers = ThrowingController.class)
@Import(GlobalExceptionHandlerTest.UnusedDao.class)
class GlobalExceptionHandlerTest {

    @TestConfiguration
    static class UnusedDao {
        /** Never invoked: no route in this slice is under {@code /v1/}, so no version is ever read. */
        @Bean
        DecisionReadDao decisionReadDao() {
            return new DecisionReadDao(null);
        }
    }

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
