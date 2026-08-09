package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.audit.AuditSource;
import com.solovis.entitlement.service.dto.ValueDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * A small, fixed development dataset — not the 100,000-account load-test seed research.md §18
 * describes (that belongs to the future entitlement-loadtest module). Runs through the same admin
 * services every real write path uses, so it can never declare data the validation rules reject.
 */
@Component
@Order(1) // after SnapshotStartup (@Order(0))
public class DemoDataSeeder implements ApplicationRunner {

    private final CapabilityAdminService capabilityService;
    private final PlanAdminService planService;
    private final AccountAdminService accountService;
    private final OverrideAdminService overrideService;
    private final AuditSource auditSource;
    private final boolean enabled;

    public DemoDataSeeder(CapabilityAdminService capabilityService, PlanAdminService planService,
            AccountAdminService accountService, OverrideAdminService overrideService, AuditSource auditSource,
            @Value("${entitlement.seed.enabled:false}") boolean enabled) {
        this.capabilityService = capabilityService;
        this.planService = planService;
        this.accountService = accountService;
        this.overrideService = overrideService;
        this.auditSource = auditSource;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || !planService.list().isEmpty()) {
            return; // already seeded, or seeding disabled (tests, and any future non-dev profile)
        }

        auditSource.runAs("SEED", () -> {
            capabilityService.create(new CapabilityCreateRequest("api.access", "API access", null, "SWITCH",
                new ValueDto("SWITCH", false, null, null, null, null), null, null));
            capabilityService.create(new CapabilityCreateRequest("reports.monthly", "Monthly reports", null, "QUANTITY",
                new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
            capabilityService.create(new CapabilityCreateRequest("seats.count", "Seats", null, "QUANTITY",
                new ValueDto("QUANTITY", null, 5L, null, null, null), null, null));
            capabilityService.create(new CapabilityCreateRequest("support.tier", "Support level", null, "TIER",
                new ValueDto("TIER", null, null, null, "community", null),
                new ValueDto("TIER", null, null, null, "community", null),
                List.of(new CapabilityCreateRequest.TierRequest("community", "Community"),
                        new CapabilityCreateRequest.TierRequest("standard", "Standard"),
                        new CapabilityCreateRequest.TierRequest("gold", "Gold"))));

            planService.create(new PlanCreateRequest("free", "Free", "Default plan for new signups."));
            planService.designateDefault("free");
            planService.create(new PlanCreateRequest("pro", "Pro", "Paid tier."));
            var proPreview = planService.preview("pro", new PlanEntitlementEditRequest(
                java.util.Map.of("api.access", new ValueDto("SWITCH", true, null, null, null, null),
                                  "reports.monthly", new ValueDto("QUANTITY", null, 50L, null, null, null),
                                  "support.tier", new ValueDto("TIER", null, null, null, "standard", null)),
                List.of(), null, null));
            planService.apply("pro", new PlanEntitlementEditRequest(
                java.util.Map.of("api.access", new ValueDto("SWITCH", true, null, null, null, null),
                                  "reports.monthly", new ValueDto("QUANTITY", null, 50L, null, null, null),
                                  "support.tier", new ValueDto("TIER", null, null, null, "standard", null)),
                List.of(), null, proPreview.previewToken()));

            accountService.create(new AccountCreateRequest("acct_9931", "Northwind Capital"));
            accountService.reassignPlan("acct_9931", new PlanReassignRequest("pro", "PERSON", "dev-operator", "Initial demo setup"));
            overrideService.create("acct_9931", new OverrideCreateRequest("reports.monthly", "GRANT",
                new ValueDto("QUANTITY", null, 200L, null, null, null), "Renewal concession — Q3 pilot"));
            accountService.create(new AccountCreateRequest("acct_1177", "Example Co"));
        });
    }
}
