package com.solovis.entitlement.service.admin;

import com.jayway.jsonpath.JsonPath;
import com.solovis.entitlement.service.store.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PlanAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired PlanRepository planRepository;

    @Test
    void createReturns201WithTheSummary() throws Exception {
        String body = """
            {"key":"plan6c-alpha","name":"Plan6c Alpha"}
            """;
        mockMvc.perform(post("/admin/v1/plans").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.key").value("plan6c-alpha"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void listIncludesTheCreatedPlan() throws Exception {
        mockMvc.perform(post("/admin/v1/plans").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"plan6c-bravo","name":"Plan6c Bravo"}
                """))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/v1/plans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plans[?(@.key == 'plan6c-bravo')]").exists());
    }

    @Test
    void getReturnsTheDetailAndPatchUpdatesTheName() throws Exception {
        mockMvc.perform(post("/admin/v1/plans").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"plan6c-charlie","name":"Plan6c Charlie"}
                """))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/v1/plans/plan6c-charlie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Plan6c Charlie"));

        mockMvc.perform(patch("/admin/v1/plans/plan6c-charlie").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Plan6c Charlie Renamed"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Plan6c Charlie Renamed"));
    }

    @Test
    void previewThenApplyEntitlementsSucceeds() throws Exception {
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"t6c.exports.count","displayName":"Exports","valueType":"QUANTITY",
                 "default":{"type":"QUANTITY","amount":0}}
                """))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/admin/v1/plans").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"plan6c-delta","name":"Plan6c Delta"}
                """))
            .andExpect(status().isCreated());

        String previewBody = """
            {"set":{"t6c.exports.count":{"type":"QUANTITY","amount":10}},"unset":[]}
            """;
        String previewResponse = mockMvc.perform(post("/admin/v1/plans/plan6c-delta/entitlements/preview")
                .contentType(MediaType.APPLICATION_JSON).content(previewBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previewToken").value(org.hamcrest.Matchers.startsWith("pv_")))
            .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(previewResponse, "$.previewToken");

        String applyBody = """
            {"set":{"t6c.exports.count":{"type":"QUANTITY","amount":10}},"unset":[],"previewToken":"%s"}
            """.formatted(token);
        mockMvc.perform(put("/admin/v1/plans/plan6c-delta/entitlements")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.planKey").value("plan6c-delta"));
    }

    @Test
    void previewThenApplyWithUnsetEntirelyOmittedFromTheBodySucceeds() throws Exception {
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"t6c.omitted-unset.count","displayName":"Omitted unset","valueType":"QUANTITY",
                 "default":{"type":"QUANTITY","amount":0}}
                """))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/admin/v1/plans").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"plan6c-golf","name":"Plan6c Golf"}
                """))
            .andExpect(status().isCreated());

        // "unset" is entirely absent from the JSON body (not an empty array) — admin-api.md's own
        // example shows set/unset as independently optional; this must not 500.
        String previewBody = """
            {"set":{"t6c.omitted-unset.count":{"type":"QUANTITY","amount":10}}}
            """;
        String previewResponse = mockMvc.perform(post("/admin/v1/plans/plan6c-golf/entitlements/preview")
                .contentType(MediaType.APPLICATION_JSON).content(previewBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(previewResponse, "$.previewToken");

        String applyBody = """
            {"set":{"t6c.omitted-unset.count":{"type":"QUANTITY","amount":10}},"previewToken":"%s"}
            """.formatted(token);
        mockMvc.perform(put("/admin/v1/plans/plan6c-golf/entitlements")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.planKey").value("plan6c-golf"));
    }

    @Test
    void archiveASimplePlanSucceeds() throws Exception {
        mockMvc.perform(post("/admin/v1/plans").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"plan6c-echo","name":"Plan6c Echo"}
                """))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/v1/plans/plan6c-echo/archive"))
            .andExpect(status().isOk());
    }

    @Test
    void designatingTheDefaultPlanSucceeds() throws Exception {
        mockMvc.perform(post("/admin/v1/plans").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"key":"plan6c-foxtrot","name":"Plan6c Foxtrot"}
                """))
            .andExpect(status().isCreated());

        mockMvc.perform(put("/admin/v1/settings/default-plan").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"planKey":"plan6c-foxtrot"}
                """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/admin/v1/plans/plan6c-foxtrot"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isDefaultForNewAccounts").value(true));

        // This test class isn't @Transactional (SnapshotPublisher's afterCommit() swap needs a real
        // commit — see PlanAdminServiceTest), so the default-plan flag it sets here would otherwise
        // leak into every other @SpringBootTest class sharing this JVM fork's SQLite file and trip
        // the single-default unique constraint (e.g. DecisionControllerTest's own seed). Clear it.
        planRepository.clearDefault("2026-08-09T00:00:00.000Z");
    }
}
