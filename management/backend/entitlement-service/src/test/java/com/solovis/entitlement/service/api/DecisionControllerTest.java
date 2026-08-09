package com.solovis.entitlement.service.api;

import com.solovis.entitlement.service.snapshot.SnapshotHolder;
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
 * Deliberately not {@code @Transactional}: {@code snapshotHolder.set(assembler.assembleFull())}
 * below reads real committed rows and publishes them straight into the shared {@link SnapshotHolder}
 * singleton, bypassing {@code SnapshotPublisher}'s commit-gated {@code afterCommit()} swap. Under
 * a rolled-back test transaction that would leak a phantom capability into the snapshot that no
 * longer exists in the database once the transaction rolls back (every read route resolves against
 * that same singleton across the whole shared Spring context, per {@link SnapshotHolder}'s javadoc).
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
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired com.solovis.entitlement.service.snapshot.SnapshotAssembler assembler;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;
    @Autowired AuditEventRepository auditEventRepository;

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
        snapshotHolder.set(assembler.assembleFull());
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
            .andExpect(header().exists("X-Entitlement-Snapshot-Version"))
            .andExpect(jsonPath("$.capabilities[?(@.key=='reports.t4.monthly')].key").value("reports.t4.monthly"))
            .andExpect(jsonPath("$.capabilities[?(@.key=='reports.t4.monthly')].area").value("reports"))
            .andExpect(jsonPath("$.capabilities[?(@.key=='reports.t4.monthly')].status").value("ACTIVE"))
            .andExpect(jsonPath("$.snapshotVersion").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
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
}
