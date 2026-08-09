package com.solovis.entitlement.service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;

/**
 * The SPA fallback had no coverage, and the gap shipped a regression: the bundle under /assets was
 * forwarded to index.html, served as text/html, and rejected by the browser's strict MIME check —
 * so every page rendered blank while every request still returned 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("a client-side route forwards to the SPA entry point")
    void clientSideRouteForwardsToTheSpa() throws Exception {
        mockMvc.perform(get("/capabilities")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/accounts")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/history")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("a nested route with a dotted capability key still forwards to the SPA")
    void nestedRouteWithDottedKeyForwardsToTheSpa() throws Exception {
        mockMvc.perform(get("/capabilities/seats.count")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/accounts/acct_9931")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("the built bundle is never forwarded — forwarding it serves HTML as JavaScript")
    void bundledAssetsAreNeverForwarded() throws Exception {
        mockMvc.perform(get("/assets/index-abc123.js")).andExpect(forwardedUrl(null));
        mockMvc.perform(get("/assets/index-abc123.css")).andExpect(forwardedUrl(null));
    }

    @Test
    @DisplayName("root-level static files are not forwarded")
    void rootLevelStaticFilesAreNotForwarded() throws Exception {
        mockMvc.perform(get("/favicon.svg")).andExpect(forwardedUrl(null));
    }

    @Test
    @DisplayName("API and actuator paths are never forwarded")
    void apiPathsAreNeverForwarded() throws Exception {
        mockMvc.perform(get("/admin/v1/meta")).andExpect(forwardedUrl(null));
        mockMvc.perform(get("/v1/accounts/nope/capabilities/nope.nope")).andExpect(forwardedUrl(null));
        mockMvc.perform(get("/actuator/health")).andExpect(forwardedUrl(null));
    }
}
