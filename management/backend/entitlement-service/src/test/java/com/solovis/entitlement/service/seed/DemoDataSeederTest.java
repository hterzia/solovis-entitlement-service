package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.audit.AuditSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructs {@link DemoDataSeeder} directly against the real, shared-context admin services
 * rather than the seeder's own {@code @Value}-bound {@code enabled} flag, so both branches of its
 * one guard ({@code !enabled}, {@code !planService.list().isEmpty()}) are exercised explicitly.
 *
 * <p>The full empty-database seed path — the one that actually calls the four admin services and
 * writes SEED-sourced audit history — is exercised by the hosted demo deployment, not here: this
 * class runs in a shared JVM fork whose SQLite file is never actually empty by the time any test
 * observes it, so the second test below deliberately creates its own plan first rather than
 * relying on execution order against other test classes to make the "already seeded" branch true.
 * That is an accepted limitation of the shared-context test harness, not something worth faking
 * around with a throwaway database.
 */
@SpringBootTest
class DemoDataSeederTest {

    @Autowired CapabilityAdminService capabilityService;
    @Autowired PlanAdminService planService;
    @Autowired AccountAdminService accountService;
    @Autowired OverrideAdminService overrideService;
    @Autowired AuditSource auditSource;

    @Test
    void seedingDisabledCreatesNoPlans() {
        int before = planService.list().size();

        new DemoDataSeeder(capabilityService, planService, accountService, overrideService, auditSource, java.time.Clock.systemUTC(), false)
            .run(null);

        assertThat(planService.list()).hasSize(before);
    }

    @Test
    void seedingEnabledAgainstAnAlreadySeededDatabaseNoOps() {
        planService.create(new PlanCreateRequest("tseed.already-here", "Already here", null));
        int before = planService.list().size();

        new DemoDataSeeder(capabilityService, planService, accountService, overrideService, auditSource, java.time.Clock.systemUTC(), true)
            .run(null);

        assertThat(planService.list()).hasSize(before);
    }
}
