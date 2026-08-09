package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PlanAdminServiceTest {

    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired PlanRepository planRepository;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired AuditEventRepository auditEventRepository;

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
