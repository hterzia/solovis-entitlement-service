package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.PlanRepository;
import com.solovis.entitlement.service.store.PlanRow;
import com.solovis.entitlement.service.time.Timestamps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PlanAdminServiceTest {

    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired AccountAdminService accountService;
    @Autowired OverrideAdminService overrideService;
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired PlanRepository planRepository;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired Clock clock;

    @Test
    void previewThenApplyWithTheReturnedTokenSucceeds() {
        capabilityService.create(new CapabilityCreateRequest("t6.reports.monthly", "Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("plan6-pro", "Plan6 Pro", null));

        var edit = new PlanEntitlementEditRequest(Map.of("t6.reports.monthly", new ValueDto("QUANTITY", null, 75L, null, null, null)),
            List.of(), null, null);
        var preview = planService.preview("plan6-pro", edit);
        assertThat(preview.previewToken()).startsWith("pv_");

        var apply = planService.apply("plan6-pro", new PlanEntitlementEditRequest(edit.set(), edit.unset(), null, preview.previewToken()));
        assertThat(apply.planKey()).isEqualTo("plan6-pro");
    }

    @Test
    void applyWithAStaleTokenIsRejected() {
        capabilityService.create(new CapabilityCreateRequest("t6.seats.count", "Seats", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("plan6-free2", "Plan6 Free 2", null));
        var edit = new PlanEntitlementEditRequest(Map.of("t6.seats.count", new ValueDto("QUANTITY", null, 5L, null, null, null)),
            List.of(), null, "pv_not-a-real-token");

        assertThatThrownBy(() -> planService.apply("plan6-free2", edit))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.PREVIEW_TOKEN_INVALID);
    }

    @Test
    void archiveAnEmptyPlanSucceeds() {
        planService.create(new PlanCreateRequest("plan6-has-accounts", "Plan6 Has accounts", null));
        // account creation is Task 7; this test only needs the plan-in-use branch reachable once
        // Task 7 exists — see Task 7's AccountAdminServiceTest for the account-bearing case. Here,
        // assert the empty-plan path archives cleanly instead:
        planService.archive("plan6-has-accounts");

        assertThat(planService.get("plan6-has-accounts").status()).isEqualTo("ARCHIVED");
    }

    /**
     * Ensures a plan with {@code key} exists (creating it ACTIVE if not), tolerating either
     * ordering between the tests below that share the "t26.default-plan" temporary-default key —
     * JUnit does not guarantee method execution order within a class, and plan keys are never
     * freed once created (archived plans still satisfy findByKey), so a plain create() in each
     * test would collide whichever one runs second.
     */
    private void ensurePlanExists(String key, String name) {
        if (planRepository.findByKey(key).isEmpty()) {
            planService.create(new PlanCreateRequest(key, name, null));
        }
    }

    /** Restores whatever plan (if any) was the designated default before a test temporarily changed it. */
    private void restoreDefault(Optional<PlanRow> previousDefault) {
        if (previousDefault.isPresent()) {
            planService.designateDefault(previousDefault.get().key());
        } else {
            planRepository.clearDefault(Timestamps.iso(clock.instant()));
        }
    }

    @Test
    void archiveFailsWhenThePlanHasAnAssignedAccount() {
        var previousDefault = planRepository.findDefault();
        try {
            planService.create(new PlanCreateRequest("t26.target", "T26 Target", null));
            ensurePlanExists("t26.default-plan", "T26 Default Plan");
            planService.designateDefault("t26.default-plan");

            accountService.create(new AccountCreateRequest("acct_t26_1", null));
            accountService.reassignPlan("acct_t26_1", new PlanReassignRequest("t26.target", null, null, null));

            assertThatThrownBy(() -> planService.archive("t26.target"))
                .isInstanceOf(EntitlementApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PLAN_IN_USE);
        } finally {
            restoreDefault(previousDefault);
        }
    }

    @Test
    void archiveFailsWhenThePlanIsTheDesignatedDefault() {
        var previousDefault = planRepository.findDefault();
        try {
            ensurePlanExists("t26.default-plan", "T26 Default Plan");
            planService.designateDefault("t26.default-plan");

            assertThatThrownBy(() -> planService.archive("t26.default-plan"))
                .isInstanceOf(EntitlementApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEFAULT_PLAN_REQUIRED);
        } finally {
            restoreDefault(previousDefault);
        }
    }

    @Test
    void previewComputesAffectedCountDiffsAndNamedAccountEffects() {
        capabilityService.create(new CapabilityCreateRequest("t26.reports.monthly", "T26 Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("t26.pro", "T26 Pro", null));

        var seedEdit = new PlanEntitlementEditRequest(
            Map.of("t26.reports.monthly", new ValueDto("QUANTITY", null, 50L, null, null, null)), List.of(), null, null);
        var seedPreview = planService.preview("t26.pro", seedEdit);
        planService.apply("t26.pro", new PlanEntitlementEditRequest(seedEdit.set(), seedEdit.unset(), null, seedPreview.previewToken()));

        var previousDefault = planRepository.findDefault();
        try {
            ensurePlanExists("t26.default-plan", "T26 Default Plan");
            planService.designateDefault("t26.default-plan");
            accountService.create(new AccountCreateRequest("acct_t26_2", null));
        } finally {
            restoreDefault(previousDefault);
        }
        accountService.reassignPlan("acct_t26_2", new PlanReassignRequest("t26.pro", null, null, null));

        var edit = new PlanEntitlementEditRequest(
            Map.of("t26.reports.monthly", new ValueDto("QUANTITY", null, 75L, null, null, null)), List.of(), "acct_t26_2", null);
        var preview = planService.preview("t26.pro", edit);

        assertThat(preview.affectedAccountCount()).isEqualTo(1);
        var diff = preview.diff().stream().filter(d -> d.capability().equals("t26.reports.monthly")).findFirst().orElseThrow();
        assertThat(diff.before().amount()).isEqualTo(50L);
        assertThat(diff.after().amount()).isEqualTo(75L);

        assertThat(preview.previewAccount()).isNotNull();
        assertThat(preview.previewAccount().effects()).hasSize(1);
        var effect = preview.previewAccount().effects().get(0);
        assertThat(effect.before().value().amount()).isEqualTo(50L);
        assertThat(effect.after().value().amount()).isEqualTo(75L);
        assertThat(effect.changed()).isTrue();
    }

    @Test
    void previewNamedAccountEffectIsUnchangedWhenAHoldAlreadyCapsTheValue() {
        capabilityService.create(new CapabilityCreateRequest("t26.reports.capped", "T26 Capped reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("t26.capped-plan", "T26 Capped Plan", null));

        var previousDefault = planRepository.findDefault();
        try {
            ensurePlanExists("t26.default-plan", "T26 Default Plan");
            planService.designateDefault("t26.default-plan");
            accountService.create(new AccountCreateRequest("acct_t26_3", null));
        } finally {
            restoreDefault(previousDefault);
        }
        accountService.reassignPlan("acct_t26_3", new PlanReassignRequest("t26.capped-plan", null, null, null));
        overrideService.create("acct_t26_3", new OverrideCreateRequest("t26.reports.capped", "HOLD",
            new ValueDto("QUANTITY", null, 0L, null, null, null), "capped for test"));

        var edit = new PlanEntitlementEditRequest(
            Map.of("t26.reports.capped", new ValueDto("QUANTITY", null, 75L, null, null, null)), List.of(), "acct_t26_3", null);
        var preview = planService.preview("t26.capped-plan", edit);

        assertThat(preview.previewAccount()).isNotNull();
        assertThat(preview.previewAccount().effects()).hasSize(1);
        var effect = preview.previewAccount().effects().get(0);
        assertThat(effect.changed()).isFalse();
        assertThat(effect.note()).isNotNull();
    }

    @Test
    void previewRejectsARetiredCapabilityInSet() {
        capabilityService.create(new CapabilityCreateRequest("tplan.retired.set", "Retired set", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        capabilityService.retire("tplan.retired.set");
        planService.create(new PlanCreateRequest("tplan-preview-retired", "Tplan Preview Retired", null));

        var edit = new PlanEntitlementEditRequest(
            Map.of("tplan.retired.set", new ValueDto("SWITCH", true, null, null, null, null)), List.of(), null, null);

        assertThatThrownBy(() -> planService.preview("tplan-preview-retired", edit))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.CAPABILITY_RETIRED_FOR_WRITE);
    }

    @Test
    void previewUnsetDiffIsNullWithADescriptiveNote() {
        capabilityService.create(new CapabilityCreateRequest("tplan.unset.switchcap", "Unset switch", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        planService.create(new PlanCreateRequest("tplan-preview-unset", "Tplan Preview Unset", null));

        var edit = new PlanEntitlementEditRequest(Map.of(), List.of("tplan.unset.switchcap"), null, null);
        var preview = planService.preview("tplan-preview-unset", edit);

        var diff = preview.diff().stream().filter(d -> d.capability().equals("tplan.unset.switchcap")).findFirst().orElseThrow();
        assertThat(diff.after()).isNull();
        assertThat(diff.note()).contains("(false)");
    }

    @Test
    void applyRejectsAnUnknownUnsetKey() {
        planService.create(new PlanCreateRequest("tplan-apply-unknown-unset", "Tplan Apply Unknown Unset", null));

        String token = PreviewTokenCodec.compute("tplan-apply-unknown-unset", Map.of(),
            List.of("tplan.does-not-exist"), snapshotHolder.current().snapshotVersion());
        var edit = new PlanEntitlementEditRequest(Map.of(), List.of("tplan.does-not-exist"), null, token);

        assertThatThrownBy(() -> planService.apply("tplan-apply-unknown-unset", edit))
            .isInstanceOf(UnknownCapabilityException.class);
    }

    @Test
    void applyAuditsPerCapabilityWithBeforeAndAfter() {
        capabilityService.create(new CapabilityCreateRequest("tplan.audit.seta", "Audit seta", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        capabilityService.create(new CapabilityCreateRequest("tplan.audit.setb", "Audit setb", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("tplan-audit", "Tplan Audit", null));

        var seedEdit = new PlanEntitlementEditRequest(Map.of(
            "tplan.audit.seta", new ValueDto("QUANTITY", null, 5L, null, null, null),
            "tplan.audit.setb", new ValueDto("QUANTITY", null, 10L, null, null, null)), List.of(), null, null);
        var seedPreview = planService.preview("tplan-audit", seedEdit);
        planService.apply("tplan-audit", new PlanEntitlementEditRequest(seedEdit.set(), seedEdit.unset(), null, seedPreview.previewToken()));

        long planId = planRepository.findByKey("tplan-audit").orElseThrow().id();
        long setaId = capabilityRepository.findByKey("tplan.audit.seta").orElseThrow().id();
        long setbId = capabilityRepository.findByKey("tplan.audit.setb").orElseThrow().id();

        int beforeCount = auditEventRepository.find(new AuditEventFilter(null, planId, null, "PLAN_ENTITLEMENT", null, null, null, 200)).size();

        var edit = new PlanEntitlementEditRequest(
            Map.of("tplan.audit.seta", new ValueDto("QUANTITY", null, 42L, null, null, null)),
            List.of("tplan.audit.setb"), null, null);
        var preview = planService.preview("tplan-audit", edit);
        planService.apply("tplan-audit", new PlanEntitlementEditRequest(edit.set(), edit.unset(), null, preview.previewToken()));

        var afterEvents = auditEventRepository.find(new AuditEventFilter(null, planId, null, "PLAN_ENTITLEMENT", null, null, null, 200));
        assertThat(afterEvents).hasSize(beforeCount + 2);

        var setEvent = afterEvents.stream().filter(e -> e.capabilityId() != null && e.capabilityId() == setaId
            && e.afterJson() != null && e.afterJson().contains("42")).findFirst().orElseThrow();
        var unsetEvent = afterEvents.stream().filter(e -> e.capabilityId() != null && e.capabilityId() == setbId
            && e.afterJson() == null).findFirst().orElseThrow();

        assertThat(setEvent.beforeJson()).contains("5");
        assertThat(setEvent.afterJson()).contains("42");
        assertThat(unsetEvent.beforeJson()).contains("10");
        assertThat(unsetEvent.afterJson()).isNull();
    }

    @Test
    void patchAuditsBothNameAndDescription() {
        planService.create(new PlanCreateRequest("tplan-patch-desc", "Tplan Patch Desc", "Original description"));
        planService.patch("tplan-patch-desc", new PlanPatchRequest(null, "Updated description"));

        long planId = planRepository.findByKey("tplan-patch-desc").orElseThrow().id();
        var events = auditEventRepository.find(new AuditEventFilter(null, planId, null, "PLAN", null, null, null, 10));
        var latest = events.stream().filter(e -> "UPDATE".equals(e.action())).findFirst().orElseThrow();

        assertThat(latest.beforeJson()).contains("Original description");
        assertThat(latest.afterJson()).contains("Updated description");
        assertThat(latest.beforeJson()).isNotEqualTo(latest.afterJson());
    }

    @Test
    void archiveAuditsStatusTransition() {
        planService.create(new PlanCreateRequest("tplan-archive-status", "Tplan Archive Status", null));
        planService.archive("tplan-archive-status");

        long planId = planRepository.findByKey("tplan-archive-status").orElseThrow().id();
        var events = auditEventRepository.find(new AuditEventFilter(null, planId, null, "PLAN", null, null, null, 10));
        var archiveEvent = events.stream().filter(e -> "ARCHIVE".equals(e.action())).findFirst().orElseThrow();

        assertThat(archiveEvent.beforeJson()).contains("ACTIVE");
        assertThat(archiveEvent.afterJson()).contains("ARCHIVED");
    }
}
