package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AccountAdminServiceTest {

    @Autowired AccountAdminService accountService;
    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired OverrideAdminService overrideService;
    @Autowired AccountRepository accountRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired PlanRepository planRepository;

    @Test
    void createAssignsTheDesignatedDefaultPlan() {
        planService.create(new PlanCreateRequest("free3", "Free 3", null));
        planService.designateDefault("free3");

        var account = accountService.create(new AccountCreateRequest("acct_new", "Acme"));

        assertThat(account.planKey()).isEqualTo("free3");
    }

    @Test
    void createFailsWithoutADesignatedDefaultPlan() {
        // Force the true no-default-plan state directly rather than relying on test ordering, then
        // exercise the real create() call — this test class isn't @Transactional (see
        // PlanAdminControllerTest's note on why), so restore whatever default plan was previously
        // designated afterwards to avoid leaking state into other tests sharing this JVM fork's SQLite file.
        var previousDefault = planRepository.findDefault();
        planRepository.clearDefault("2026-08-09T00:00:00.000Z");
        try {
            assertThatThrownBy(() -> accountService.create(new AccountCreateRequest("acct_t26acct_1", "No Default Plan Co")))
                .isInstanceOf(EntitlementApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEFAULT_PLAN_REQUIRED);
        } finally {
            previousDefault.ifPresent(row -> planRepository.setDefault(row.id(), "2026-08-09T00:00:00.000Z"));
        }
    }

    @Test
    void getReturnsSourceMarkingAndPlanReassignRetainsOverrides() {
        planService.create(new PlanCreateRequest("pro3", "Pro 3", null));
        planService.designateDefault("pro3");
        capabilityService.create(new CapabilityCreateRequest("t7.reports.monthly", "Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_view", null));
        overrideService.create("acct_view", new OverrideCreateRequest("t7.reports.monthly", "GRANT",
            new ValueDto("QUANTITY", null, 200L, null, null, null), "renewal concession"));

        var detail = accountService.get("acct_view");

        var entitlement = detail.entitlements().stream()
            .filter(e -> e.capability().equals("t7.reports.monthly")).findFirst().orElseThrow();
        assertThat(entitlement.source()).isEqualTo("GRANT");
        assertThat(detail.overrides()).extracting(AccountDetailDto.OverrideRow::effectNow).containsExactly("WINNING");

        planService.create(new PlanCreateRequest("enterprise3", "Enterprise 3", null));
        var richerEdit = new PlanEntitlementEditRequest(
            Map.of("t7.reports.monthly", new ValueDto("QUANTITY", null, 500L, null, null, null)), List.of(), null, null);
        var richerPreview = planService.preview("enterprise3", richerEdit);
        planService.apply("enterprise3", new PlanEntitlementEditRequest(richerEdit.set(), richerEdit.unset(), null, richerPreview.previewToken()));

        var reassign = accountService.reassignPlan("acct_view",
            new PlanReassignRequest("enterprise3", "SYSTEM", "billing-sync", "Subscription upgraded"));
        assertThat(reassign.retainedOverrideCount()).isEqualTo(1);

        // the retained GRANT (200) now loses to the richer plan baseline (500) — the exact trigger for
        // effectNow's Critical bug: it was mislabeled SUPERSEDED_BY_GRANT (implying a competing grant
        // that doesn't exist) instead of NO_EFFECT_PLAN_MORE_GENEROUS.
        var afterUpgrade = accountService.get("acct_view");
        assertThat(afterUpgrade.overrides()).extracting(AccountDetailDto.OverrideRow::effectNow)
            .containsExactly("NO_EFFECT_PLAN_MORE_GENEROUS");
    }

    @Test
    void reassignPlanRejectsAnUnknownSourceInsteadOfThrowingUnhandled() {
        planService.create(new PlanCreateRequest("t7-bad-source-plan", "T7 Bad Source Plan", null));
        planService.designateDefault("t7-bad-source-plan");
        accountService.create(new AccountCreateRequest("acct_bad_source", null));

        assertThatThrownBy(() -> accountService.reassignPlan("acct_bad_source",
            new PlanReassignRequest("t7-bad-source-plan", "UI", "someone", "bad source value")))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void effectNowLabelsALoneGrantThatDoesNotBeatThePlanBaseline() {
        planService.create(new PlanCreateRequest("t7-baseline-plan", "T7 Baseline Plan", null));
        planService.designateDefault("t7-baseline-plan");
        capabilityService.create(new CapabilityCreateRequest("t7.grant.only", "Grant only test", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        var edit = new PlanEntitlementEditRequest(
            Map.of("t7.grant.only", new ValueDto("QUANTITY", null, 100L, null, null, null)), List.of(), null, null);
        var preview = planService.preview("t7-baseline-plan", edit);
        planService.apply("t7-baseline-plan", new PlanEntitlementEditRequest(edit.set(), edit.unset(), null, preview.previewToken()));
        accountService.create(new AccountCreateRequest("acct_grant_only", null));

        // the only override on this capability — no competing GRANT, no HOLD — so a plan-beats-grant
        // outcome must never be reported as SUPERSEDED_BY_GRANT.
        overrideService.create("acct_grant_only", new OverrideCreateRequest("t7.grant.only", "GRANT",
            new ValueDto("QUANTITY", null, 50L, null, null, null), "grant that loses to the plan baseline"));

        var detail = accountService.get("acct_grant_only");
        assertThat(detail.overrides()).extracting(AccountDetailDto.OverrideRow::effectNow)
            .containsExactly("NO_EFFECT_PLAN_MORE_GENEROUS");
    }

    @Test
    void createRecordsAnAuditEventVisibleToTheAccountFilter() {
        planService.create(new PlanCreateRequest("tacct.default-plan", "Tacct Default Plan", null));
        planService.designateDefault("tacct.default-plan");

        accountService.create(new AccountCreateRequest("acct_tacct_1", "Tacct One"));

        var accountRow = accountRepository.findByExternalId("acct_tacct_1").orElseThrow();
        var defaultPlan = planRepository.findByKey("tacct.default-plan").orElseThrow();

        var events = auditEventRepository.find(
            new AuditEventFilter(accountRow.id(), null, null, "ACCOUNT", null, null, null, 10));

        assertThat(events).hasSize(1);
        var event = events.get(0);
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.planId()).isEqualTo(defaultPlan.id());
        assertThat(event.afterJson()).contains("tacct.default-plan");
    }

    @Test
    void searchPagesByCursorWhenMoreAccountsExist() {
        planService.create(new PlanCreateRequest("cursor-test-plan", "Cursor test plan", null));
        planService.designateDefault("cursor-test-plan");
        accountService.create(new AccountCreateRequest("acct_cursor_page_0", null));
        accountService.create(new AccountCreateRequest("acct_cursor_page_1", null));
        accountService.create(new AccountCreateRequest("acct_cursor_page_2", null));

        var firstPage = accountService.search("acct_cursor_page", null, 0, 2);
        assertThat(firstPage.accounts()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotNull();

        long afterId = com.solovis.entitlement.service.error.RefId.parse(firstPage.nextCursor(), "acct_");
        var secondPage = accountService.search("acct_cursor_page", null, afterId, 2);
        assertThat(secondPage.accounts()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
    }
}
