package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * GET /admin/v1/meta — the UI reads the "Saved. Active everywhere within {N} seconds." promise
 * (c41) and the answer-reuse window from here, never a hard-coded constant.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetaControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired CapabilityAdminService capabilityAdminService;

    @Test
    void metaReturnsThePublishedChangeVisibilityAnswerReuseSnapshotVersionAndCapabilityAreas() throws Exception {
        // Publish at least one write first so snapshotVersion is guaranteed >= 1 even if this test
        // runs in isolation against a fresh database (a shared-suite run would already satisfy this).
        capabilityAdminService.create(new CapabilityCreateRequest("t31.meta.probe", "T31 meta probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));

        mockMvc.perform(get("/admin/v1/meta"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changeVisibleEverywhereWithinSeconds").value(60))
            .andExpect(jsonPath("$.answerReuseMaxSeconds").value(10))
            .andExpect(jsonPath("$.snapshotVersion").isNumber())
            .andExpect(jsonPath("$.snapshotVersion").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.capabilityAreas").isArray())
            // No GOOGLE_AI_GEMINI_API_KEY in the test environment, so no interpreter bean exists.
            .andExpect(jsonPath("$.askEnabled").value(false));
    }
}
