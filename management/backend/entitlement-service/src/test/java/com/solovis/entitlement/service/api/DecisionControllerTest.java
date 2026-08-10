package com.solovis.entitlement.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Deliberately not {@code @Transactional}: {@code @BeforeAll} below commits fixtures through the
 * write-side repositories, and {@link DecisionController} now reads straight out of SQLite via
 * {@link DecisionReadService} — only the read pool's own transaction sees committed rows, so this
 * class's fixtures have to actually be committed, not rolled back at the end of each test method.
 * Fixture keys are namespaced ("*.t4.*"/"t4-*"/"acct_t4_*") so this class's permanent writes don't collide with other
 * non-transactional {@code @SpringBootTest} classes sharing this JVM fork's SQLite file. Seeding runs
 * once via {@code @BeforeAll} (not {@code @BeforeEach}) since the unique key/external_id indexes would
 * reject a second insert of the same fixtures once nothing rolls them back between test methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecisionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired PlanAdminService planAdminService;
    @Autowired CapabilityAdminService capabilityAdminService;
    @Autowired AccountAdminService accountAdminService;
    @Autowired OverrideAdminService overrideAdminService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    void seedAndRefreshSnapshot() {
        long capId = capabilityRepository.insert(new CapabilityRow(null, "reports.t4.monthly", "reports",
            "Monthly reports", null, "QUANTITY", null, 0L, false, null, false, null, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        // GAP 1 fixture: a RETIRED capability so the decision route's 409 retired-capability
        // path (Resolver.explain -> RetiredCapabilityException -> GlobalExceptionHandler) has
        // something to exercise. Mirrors the ACTIVE capability's insert shape above, just with
        // status/retiredAt set, and namespaced "t28" so it can't collide with another class's fixtures.
        capabilityRepository.insert(new CapabilityRow(null, "reports.t28.retired", "reports",
            "Retired monthly reports", null, "QUANTITY", null, 0L, false, null, false, null, null, "RETIRED",
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        // Not the default plan: the schema permits exactly one, SchemaInvariantsTest
        // legitimately claims it, and tests sharing a Spring context share one SQLite
        // file — so claiming it here makes the pair order-dependent. The account below
        // is assigned this plan explicitly, so default-for-new-accounts is irrelevant here.
        long planId = planRepository.insert(new PlanRow(null, "t4-free", "Free", null, "ACTIVE", false,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        accountRepository.insert(new AccountRow(null, "acct_t4_1", null, planId, "2026-08-09T00:00:00.000Z",
            "PERSON", "dev-operator", "ACTIVE", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        long auditSeq = auditEventRepository.insert(AuditEventRow.operatorAct(null, "2026-08-09T00:00:00.000Z", "PERSON",
            "dev-operator", "UI", "PLAN", "t4-free", "CREATE", null, null, null, null, null, null, null));
        snapshotVersionRepository.insert(new SnapshotVersionRow(null, "2026-08-09T00:00:00.000Z", auditSeq, "{}"));
    }

    @Test
    void singleCapabilityReturnsFullTrace() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_t4_1/capabilities/reports.t4.monthly"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Entitlement-Snapshot-Version"))
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.trace.baseline.source").value("CAPABILITY_DEFAULT"))
            .andExpect(jsonPath("$.trace.grantStep.applied").value(false))
            .andExpect(jsonPath("$.trace.grantStep.why").value("NO_GRANTS"));
    }

    @Test
    void unknownAccountIsAnErrorNeverADenial() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_missing/capabilities/reports.t4.monthly"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("entitlement/unknown-account"));
    }

    // contracts/README.md scopes the header to "every /v1 response", not every /v1 *success*. An
    // error is exactly when a caller most wants to know which snapshot answered it — a 404 from a
    // replica-lagging read and a 404 from a genuinely absent account look identical otherwise.
    @Test
    void errorResponsesOnV1AlsoCarryTheSnapshotVersionHeader() throws Exception {
        long current = snapshotVersionRepository.findLatest().map(row -> row.version()).orElse(0L);

        mockMvc.perform(get("/v1/accounts/acct_missing/capabilities/reports.t4.monthly"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Entitlement-Snapshot-Version", String.valueOf(current)));

        mockMvc.perform(get("/v1/accounts/acct_t4_1/capabilities/reports.t28.retired"))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Entitlement-Snapshot-Version", String.valueOf(current)));

        mockMvc.perform(get("/v1/capabilities").param("status", "nonsense"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(header().string("X-Entitlement-Snapshot-Version", String.valueOf(current)));
    }

    // The header is a /v1 product-contract promise. /admin/v1 backs the SPA and may change with it,
    // so the error handler does not stamp it there. The one admin route that does carry it is
    // /admin/v1/check, which admin-api.md defines as returning the /v1 payload "byte for byte" and
    // therefore copies the upstream response's headers on purpose.
    @Test
    void adminResponsesDoNotCarryTheSnapshotVersionHeader() throws Exception {
        mockMvc.perform(get("/admin/v1/meta"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Entitlement-Snapshot-Version"));
    }

    @Test
    void wholeAccountOmitsTraces() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_t4_1/entitlements"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entitlements[?(@.capability=='reports.t4.monthly')].capability").value("reports.t4.monthly"))
            .andExpect(jsonPath("$.entitlements[?(@.capability=='reports.t4.monthly')].trace").doesNotExist());
    }

    @Test
    void registryDefaultsToActiveOnly() throws Exception {
        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Entitlement-Snapshot-Version"))
            .andExpect(jsonPath("$.capabilities[?(@.key=='reports.t4.monthly')].key").value("reports.t4.monthly"))
            .andExpect(jsonPath("$.capabilities[?(@.key=='reports.t4.monthly')].area").value("reports"))
            .andExpect(jsonPath("$.capabilities[?(@.key=='reports.t4.monthly')].status").value("ACTIVE"))
            .andExpect(jsonPath("$.snapshotVersion").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    /**
     * c30: a write committed on the write pool must be immediately visible to the read pool — never
     * a 409 on the very next request. Drives a real admin write (an override create, through
     * {@link OverrideAdminService}, the actual {@code @Transactional} write path admin controllers
     * use) and asserts the {@code snapshotVersion} it returns is honoured, not just eventually reached.
     */
    @Test
    void readYourWritesAcrossThePoolBoundary() throws Exception {
        planAdminService.create(new PlanCreateRequest("t4-c30-plan", "T4 C30 Plan", null));
        planAdminService.designateDefault("t4-c30-plan");
        capabilityAdminService.create(new CapabilityCreateRequest("reports.t4.c30", "C30 probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountAdminService.create(new AccountCreateRequest("acct_t4_c30", null));

        var created = overrideAdminService.create("acct_t4_c30", new OverrideCreateRequest("reports.t4.c30", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "c30 read-your-writes probe"));
        long publishedVersion = created.snapshotVersion();

        mockMvc.perform(get("/v1/accounts/acct_t4_c30/capabilities/reports.t4.c30")
                .param("minSnapshotVersion", String.valueOf(publishedVersion)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void malformedCapabilityKeyInSingleDecisionRouteIsUnknownCapabilityNotA500() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_t4_1/capabilities/Reports.monthly"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("entitlement/unknown-capability"));
    }

    @Test
    void registryRejectsUnrecognizedStatusValue() throws Exception {
        mockMvc.perform(get("/v1/capabilities").param("status", "BOGUS"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"))
            .andExpect(jsonPath("$.violations").exists());
    }

    @Test
    void registryRejectsWrongCaseStatusValue() throws Exception {
        mockMvc.perform(get("/v1/capabilities").param("status", "retired"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"))
            .andExpect(jsonPath("$.violations").exists());
    }

    @Test
    void singleCapabilityDescriptorCarriesSnapshotVersionHeader() throws Exception {
        mockMvc.perform(get("/v1/capabilities/reports.t4.monthly"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Entitlement-Snapshot-Version"));
    }

    @Test
    void retiredCapabilityOnSingleDecisionRouteIsAConflictNotADenial() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_t4_1/capabilities/reports.t28.retired"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("entitlement/retired-capability"));
    }

    @Test
    void minSnapshotVersionAheadOfCurrentIsSnapshotBehind() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_t4_1/capabilities/reports.t4.monthly")
                .param("minSnapshotVersion", "999999999"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("entitlement/snapshot-behind"))
            .andExpect(jsonPath("$.currentVersion").isNumber());
    }

    @Test
    void singleCapabilityRouteIsCacheableForTenSecondsAndStaleForADay() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_t4_1/capabilities/reports.t4.monthly"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=10, stale-if-error=86400"));
    }

    @Test
    void wholeAccountAnswersMatchSingleCapabilityAnswersForEveryEntitlement() throws Exception {
        String wholeAccountBody = mockMvc.perform(get("/v1/accounts/acct_t4_1/entitlements"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode entitlements = MAPPER.readTree(wholeAccountBody).get("entitlements");
        assertThat(entitlements.isArray()).isTrue();
        assertThat(entitlements.size()).isGreaterThan(0);

        for (JsonNode entitlement : entitlements) {
            String capability = entitlement.get("capability").asText();
            boolean wholeAccountAllowed = entitlement.get("allowed").asBoolean();
            JsonNode wholeAccountValue = entitlement.get("value");

            String singleBody = mockMvc.perform(get("/v1/accounts/acct_t4_1/capabilities/" + capability))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            JsonNode single = MAPPER.readTree(singleBody);

            assertThat(single.get("allowed").asBoolean())
                .as("allowed for capability %s", capability)
                .isEqualTo(wholeAccountAllowed);
            assertThat(single.get("value"))
                .as("value for capability %s", capability)
                .isEqualTo(wholeAccountValue);
        }
    }
}
