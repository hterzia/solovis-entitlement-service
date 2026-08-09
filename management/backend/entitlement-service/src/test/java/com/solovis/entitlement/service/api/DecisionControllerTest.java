package com.solovis.entitlement.service.api;

import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DecisionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired com.solovis.entitlement.service.snapshot.SnapshotAssembler assembler;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;
    @Autowired AuditEventRepository auditEventRepository;

    @BeforeEach
    void seedAndRefreshSnapshot() {
        long capId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", "reports",
            "Monthly reports", null, "QUANTITY", null, 0L, false, null, false, null, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        long planId = planRepository.insert(new PlanRow(null, "free", "Free", null, "ACTIVE", true,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        accountRepository.insert(new AccountRow(null, "acct_1", null, planId, "2026-08-09T00:00:00.000Z",
            "PERSON", "dev-operator", "ACTIVE", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        long auditSeq = auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "PERSON",
            "dev-operator", "UI", "PLAN", "free", "CREATE", null, null, null, null, null, null, null));
        snapshotVersionRepository.insert(new SnapshotVersionRow(null, "2026-08-09T00:00:00.000Z", auditSeq, "{}"));
        snapshotHolder.set(assembler.assembleFull());
    }

    @Test
    void singleCapabilityReturnsFullTrace() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_1/capabilities/reports.monthly"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Entitlement-Snapshot-Version"))
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.trace.baseline.source").value("CAPABILITY_DEFAULT"))
            .andExpect(jsonPath("$.trace.grantStep.applied").value(false))
            .andExpect(jsonPath("$.trace.grantStep.why").value("NO_GRANTS"));
    }

    @Test
    void unknownAccountIsAnErrorNeverADenial() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_missing/capabilities/reports.monthly"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("entitlement/unknown-account"));
    }

    @Test
    void wholeAccountOmitsTraces() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_1/entitlements"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entitlements[0].capability").value("reports.monthly"))
            .andExpect(jsonPath("$.entitlements[0].trace").doesNotExist());
    }

    @Test
    void registryDefaultsToActiveOnly() throws Exception {
        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.capabilities[0].key").value("reports.monthly"))
            .andExpect(jsonPath("$.snapshotVersion").value(1));
    }
}
