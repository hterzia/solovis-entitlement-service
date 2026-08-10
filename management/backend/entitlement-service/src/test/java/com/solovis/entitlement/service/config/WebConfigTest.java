package com.solovis.entitlement.service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SPA fallback had no coverage, and the gap shipped a regression that nothing else could see:
 * the JavaScript bundle was forwarded to index.html and served as text/html, so the browser
 * rejected it under strict MIME checking and every page rendered blank — while every request
 * returned 200, nothing 404'd, and the health check stayed green.
 *
 * <p>The decisive assertion is {@link #theBundleIsServedAsItselfNotTheSpaShell()}: it is not enough
 * that /assets/... returns 200, because the broken version did too. What matters is <i>which
 * bytes</i> come back.
 *
 * <p>Fixtures live in src/test/resources/static — the real Vite output only exists in the packaged
 * image, so without them there is no file to prove "a real file wins" against.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebConfigTest {

    private static final String SPA_MARKER = "SPA_ENTRY_POINT_MARKER";
    private static final String BUNDLE_MARKER = "REAL_BUNDLE_NOT_THE_SPA_SHELL";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("the bundle is served as itself, not as the SPA shell")
    void theBundleIsServedAsItselfNotTheSpaShell() throws Exception {
        mockMvc.perform(get("/assets/test-bundle.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(BUNDLE_MARKER)))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SPA_MARKER))));
    }

    @Test
    @DisplayName("a client-side route serves the SPA entry point")
    void clientSideRouteServesTheSpaEntryPoint() throws Exception {
        for (String route : new String[] {"/capabilities", "/accounts", "/history", "/checker", "/plans"}) {
            mockMvc.perform(get(route))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(SPA_MARKER)));
        }
    }

    @Test
    @DisplayName("a nested route with a dotted capability key still serves the SPA")
    void nestedRouteWithDottedKeyServesTheSpa() throws Exception {
        mockMvc.perform(get("/capabilities/seats.count"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(SPA_MARKER)));
        mockMvc.perform(get("/accounts/acct_9931"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(SPA_MARKER)));
    }

    @Test
    @DisplayName("a missing asset 404s rather than silently returning the SPA shell")
    void missingAssetIsNotFound() throws Exception {
        mockMvc.perform(get("/assets/does-not-exist.js")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unmatched API path 404s rather than returning HTML to a JSON caller")
    void unmatchedApiPathIsNotFound() throws Exception {
        mockMvc.perform(get("/admin/v1/no-such-endpoint")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/no-such-endpoint")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a real API route is untouched by the fallback")
    void realApiRouteIsUntouched() throws Exception {
        mockMvc.perform(get("/admin/v1/meta"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("changeVisibleEverywhereWithinSeconds")));
    }
}
