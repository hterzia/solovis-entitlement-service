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
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private final CapabilityAdminService capabilityService;
    private final PlanAdminService planService;
    private final AccountAdminService accountService;
    private final OverrideAdminService overrideService;
    private final AuditSource auditSource;
    private final java.time.Clock clock;
    private final boolean enabled;

    public DemoDataSeeder(CapabilityAdminService capabilityService, PlanAdminService planService,
            AccountAdminService accountService, OverrideAdminService overrideService, AuditSource auditSource,
            java.time.Clock clock, @Value("${entitlement.seed.enabled:false}") boolean enabled) {
        this.capabilityService = capabilityService;
        this.planService = planService;
        this.accountService = accountService;
        this.overrideService = overrideService;
        this.auditSource = auditSource;
        this.clock = clock;
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

            // 002: one override in each standing an operator can be shown today, so screen 3's
            // grouping and the checker's not-in-force entries have something real to render.
            //
            // Deliberately on acct_1177, never acct_9931: the e2e suite asserts on acct_9931's
            // *resolved* state, and another override on its capabilities would change the answer
            // and fail tests that are perfectly correct.
            //
            // ENDED is missing from this list on purpose. c7 forbids saving a wholly-past window
            // through the API, and the seeder writes through the same admin services as everything
            // else, so it cannot manufacture one either. The seats.count grant below expires
            // *today*, which means the demo shows a real ending when the clock next passes midnight
            // — a better demonstration of c12 than a row that was born expired.
            var today = java.time.LocalDate.now(clock);
            overrideService.create("acct_1177", new OverrideCreateRequest("seats.count", "GRANT",
                new ValueDto("QUANTITY", null, 25L, null, null, null),
                "Trial seats through the end of today", null, today.toString()));
            overrideService.create("acct_1177", new OverrideCreateRequest("reports.monthly", "GRANT",
                new ValueDto("QUANTITY", null, 500L, null, null, null),
                "Reporting pilot agreed for next month", today.plusDays(30).toString(), today.plusDays(120).toString()));
            var lifted = overrideService.create("acct_1177", new OverrideCreateRequest("api.access", "HOLD",
                new ValueDto("SWITCH", false, null, null, null, null),
                "Suspended pending investigation"));
            overrideService.delete("acct_1177", lifted.overrideId(), "Investigation closed, access restored");
        });
    }
}
