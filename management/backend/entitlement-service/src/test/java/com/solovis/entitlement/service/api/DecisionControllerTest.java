package com.solovis.entitlement.service.api;

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

    @BeforeAll
    void seedAndRefreshSnapshot() {
        long capId = capabilityRepository.insert(new CapabilityRow(null, "reports.t4.monthly", "reports",
            "Monthly reports", null, "QUANTITY", null, 0L, false, null, false, null, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        // Not the default plan: the schema permits exactly one, SchemaInvariantsTest
        // legitimately claims it, and tests sharing a Spring context share one SQLite
        // file — so claiming it here makes the pair order-dependent. The account below
        // is assigned this plan explicitly, so default-for-new-accounts is irrelevant here.
        long planId = planRepository.insert(new PlanRow(null, "t4-free", "Free", null, "ACTIVE", false,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        accountRepository.insert(new AccountRow(null, "acct_t4_1", null, planId, "2026-08-09T00:00:00.000Z",
            "PERSON", "dev-operator", "ACTIVE", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        long auditSeq = auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "PERSON",
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
}
