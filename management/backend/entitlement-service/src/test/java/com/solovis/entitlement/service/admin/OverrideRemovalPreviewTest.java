package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.api.dto.DecisionResponseDto;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The remove-override confirmation has to show what the value returns to *before* the operator
 * confirms (ui-screens.md, screen 3 — c14, c15). The preview must therefore compute that answer
 * from the same resolver as every other surface, and must leave nothing behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OverrideRemovalPreviewTest {

    @Autowired MockMvc mockMvc;
    @Autowired OverrideAdminService overrideService;
    @Autowired AccountAdminService accountService;
    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountOverrideRepository accountOverrideRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;

    /** c15 — the winning GRANT is what the operator is about to remove; the answer falls back to the plan. */
    @Test
    void removingTheWinningGrantReturnsThePlanBaselineAndDropsItFromTheTrace() {
        seedPlanWithQuantity("tprev-plan-1", "tprev.seats.count", 100L);
        accountService.create(new AccountCreateRequest("tprev_acct_1", null));
        var grant = overrideService.create("tprev_acct_1", new OverrideCreateRequest("tprev.seats.count", "GRANT",
            new ValueDto("QUANTITY", null, 500L, null, null, null), "expansion pilot"));
        assertThat(grant.decision().value().amount()).isEqualTo(500L);

        var preview = overrideService.previewRemoval("tprev_acct_1", grant.overrideId());

        assertThat(preview.value().amount()).isEqualTo(100L);
        assertThat(preview.trace().baseline().source()).isEqualTo("PLAN");
        assertThat(preview.trace().baseline().planKey()).isEqualTo("tprev-plan-1");
        assertThat(preview.trace().grants()).extracting(DecisionResponseDto.TraceDto.CandidateDto::overrideId)
            .doesNotContain(grant.overrideId());
        assertThat(preview.trace().grantStep().applied()).isFalse();
        assertThat(preview.trace().grantStep().why()).isEqualTo("NO_GRANTS");
        assertThat(preview.account()).isEqualTo("tprev_acct_1");
        assertThat(preview.capability()).isEqualTo("tprev.seats.count");
        assertThat(preview.snapshotVersion()).isEqualTo(snapshotVersionRepository.findLatest().orElseThrow().version());
        assertThat(preview.evaluatedAt()).isNotBlank();
    }

    /** c14 — a HOLD caps a GRANT; removing the HOLD must uncover the GRANT again, not the plan. */
    @Test
    void removingAHoldRestoresTheGrantUnderneathIt() {
        planService.create(new PlanCreateRequest("tprev-plan-2", "Preview Plan 2", null));
        planService.designateDefault("tprev-plan-2");
        capabilityService.create(new CapabilityCreateRequest("tprev.api.access", "API access", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("tprev_acct_2", null));

        var grant = overrideService.create("tprev_acct_2", new OverrideCreateRequest("tprev.api.access", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "trial access"));
        assertThat(grant.decision().allowed()).isTrue();
        var hold = overrideService.create("tprev_acct_2", new OverrideCreateRequest("tprev.api.access", "HOLD",
            new ValueDto("SWITCH", false, null, null, null, null), "suspended pending investigation"));
        assertThat(hold.decision().allowed()).isFalse();

        var preview = overrideService.previewRemoval("tprev_acct_2", hold.overrideId());

        assertThat(preview.value().enabled()).isTrue();
        assertThat(preview.allowed()).isTrue();
        assertThat(preview.trace().holds()).extracting(DecisionResponseDto.TraceDto.CandidateDto::overrideId)
            .doesNotContain(hold.overrideId());
        assertThat(preview.trace().holdStep().applied()).isFalse();
        assertThat(preview.trace().grantStep().applied()).isTrue();
        assertThat(preview.trace().grantStep().winner()).isEqualTo(grant.overrideId());
    }

    /** c15 again, on a TIER capability — the plan's tier is what a removed GRANT falls back to. */
    @Test
    void removingATierGrantRestoresThePlanTier() {
        planService.create(new PlanCreateRequest("tprev-plan-3", "Preview Plan 3", null));
        planService.designateDefault("tprev-plan-3");
        capabilityService.create(new CapabilityCreateRequest("tprev.support.tier", "Support", null, "TIER",
            new ValueDto("TIER", null, null, null, "bronze", null), null,
            List.of(new CapabilityCreateRequest.TierRequest("bronze", "Bronze"),
                new CapabilityCreateRequest.TierRequest("silver", "Silver"),
                new CapabilityCreateRequest.TierRequest("gold", "Gold"))));
        var edit = new PlanEntitlementEditRequest(
            Map.of("tprev.support.tier", new ValueDto("TIER", null, null, null, "silver", null)), List.of(), null, null);
        planService.apply("tprev-plan-3", new PlanEntitlementEditRequest(edit.set(), edit.unset(), null,
            planService.preview("tprev-plan-3", edit).previewToken()));
        accountService.create(new AccountCreateRequest("tprev_acct_3", null));

        var grant = overrideService.create("tprev_acct_3", new OverrideCreateRequest("tprev.support.tier", "GRANT",
            new ValueDto("TIER", null, null, null, "gold", null), "escalated account"));
        assertThat(grant.decision().value().tier()).isEqualTo("gold");

        var preview = overrideService.previewRemoval("tprev_acct_3", grant.overrideId());

        assertThat(preview.value().tier()).isEqualTo("silver");
        assertThat(preview.trace().baseline().source()).isEqualTo("PLAN");
    }

    /** Removing an override that never decided anything must not change the answer. */
    @Test
    void previewingANonWinningOverrideReturnsTheUnchangedDecision() {
        seedPlanWithQuantity("tprev-plan-4", "tprev.reports.count", 100L);
        accountService.create(new AccountCreateRequest("tprev_acct_4", null));

        var loser = overrideService.create("tprev_acct_4", new OverrideCreateRequest("tprev.reports.count", "GRANT",
            new ValueDto("QUANTITY", null, 200L, null, null, null), "smaller grant"));
        var winner = overrideService.create("tprev_acct_4", new OverrideCreateRequest("tprev.reports.count", "GRANT",
            new ValueDto("QUANTITY", null, 500L, null, null, null), "larger grant"));
        assertThat(winner.decision().value().amount()).isEqualTo(500L);

        var preview = overrideService.previewRemoval("tprev_acct_4", loser.overrideId());

        assertThat(preview.value()).isEqualTo(winner.decision().value());
        assertThat(preview.allowed()).isEqualTo(winner.decision().allowed());
        assertThat(preview.trace().grantStep().winner()).isEqualTo(winner.overrideId());
        assertThat(preview.trace().grants()).extracting(DecisionResponseDto.TraceDto.CandidateDto::overrideId)
            .containsExactly(winner.overrideId());
    }

    /** A preview is a read: nothing removed, nothing audited, no new snapshot version. */
    @Test
    void previewMutatesNothing() {
        seedPlanWithQuantity("tprev-plan-5", "tprev.exports.count", 10L);
        accountService.create(new AccountCreateRequest("tprev_acct_5", null));
        var grant = overrideService.create("tprev_acct_5", new OverrideCreateRequest("tprev.exports.count", "GRANT",
            new ValueDto("QUANTITY", null, 99L, null, null, null), "temporary bump"));

        long accountId = accountRepository.findByExternalId("tprev_acct_5").orElseThrow().id();
        long latestRowBefore = snapshotVersionRepository.findLatest().orElseThrow().version();
        int auditCountBefore = auditEventRepository
            .find(new AuditEventFilter(accountId, null, null, null, null, null, null, null, 100)).size();

        overrideService.previewRemoval("tprev_acct_5", grant.overrideId());

        var row = accountOverrideRepository.findById(Long.parseLong(grant.overrideId().substring("ovr_".length())))
            .orElseThrow();
        assertThat(row.removedAt()).isNull();
        assertThat(accountOverrideRepository.findLiveForAccount(accountId))
            .extracting(o -> "ovr_" + o.id()).contains(grant.overrideId());
        assertThat(auditEventRepository.find(new AuditEventFilter(accountId, null, null, null, null, null, null, null, 100)))
            .hasSize(auditCountBefore);
        assertThat(snapshotVersionRepository.findLatest().orElseThrow().version()).isEqualTo(latestRowBefore);
    }

    @Test
    void unknownAccountIsTheUnknownAccountProblem() throws Exception {
        seedPlanWithQuantity("tprev-plan-6", "tprev.unknown.count", 5L);
        accountService.create(new AccountCreateRequest("tprev_acct_6", null));
        var grant = overrideService.create("tprev_acct_6", new OverrideCreateRequest("tprev.unknown.count", "GRANT",
            new ValueDto("QUANTITY", null, 50L, null, null, null), "grant for the unknown-account probe"));

        assertThatThrownBy(() -> overrideService.previewRemoval("tprev_no_such_account", grant.overrideId()))
            .isInstanceOf(com.solovis.entitlement.core.error.UnknownAccountException.class);

        mockMvc.perform(get("/admin/v1/accounts/{external}/overrides/{id}/removal-preview",
                "tprev_no_such_account", grant.overrideId()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("entitlement/unknown-account"));
    }

    @Test
    void unknownOverrideIdIsRejected() throws Exception {
        seedPlanWithQuantity("tprev-plan-7", "tprev.missing.count", 5L);
        accountService.create(new AccountCreateRequest("tprev_acct_7", null));

        assertThatThrownBy(() -> overrideService.previewRemoval("tprev_acct_7", "ovr_99999999"))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> overrideService.previewRemoval("tprev_acct_7", "ovr_not-a-number"))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);

        mockMvc.perform(get("/admin/v1/accounts/{external}/overrides/{id}/removal-preview",
                "tprev_acct_7", "ovr_99999999"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }

    /** An override already gone has no removal to preview, and it belongs to no other account either. */
    @Test
    void alreadyRemovedAndForeignOverridesAreRejected() {
        seedPlanWithQuantity("tprev-plan-8", "tprev.gone.count", 5L);
        accountService.create(new AccountCreateRequest("tprev_acct_8a", null));
        accountService.create(new AccountCreateRequest("tprev_acct_8b", null));
        var grant = overrideService.create("tprev_acct_8a", new OverrideCreateRequest("tprev.gone.count", "GRANT",
            new ValueDto("QUANTITY", null, 50L, null, null, null), "about to be removed"));

        assertThatThrownBy(() -> overrideService.previewRemoval("tprev_acct_8b", grant.overrideId()))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);

        overrideService.delete("tprev_acct_8a", grant.overrideId(), "no longer needed");

        assertThatThrownBy(() -> overrideService.previewRemoval("tprev_acct_8a", grant.overrideId()))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** The resolver refuses a retired capability, so the preview reports that rather than a decision. */
    @Test
    void previewOnARetiredCapabilityIsTheRetiredCapabilityProblem() throws Exception {
        seedPlanWithQuantity("tprev-plan-9", "tprev.retired.count", 5L);
        accountService.create(new AccountCreateRequest("tprev_acct_9", null));
        var grant = overrideService.create("tprev_acct_9", new OverrideCreateRequest("tprev.retired.count", "GRANT",
            new ValueDto("QUANTITY", null, 50L, null, null, null), "grant before retirement"));
        capabilityService.retire("tprev.retired.count");

        mockMvc.perform(get("/admin/v1/accounts/{external}/overrides/{id}/removal-preview",
                "tprev_acct_9", grant.overrideId()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("entitlement/retired-capability"));
    }

    /** The route returns the decision payload the SPA's TraceView already renders. */
    @Test
    void theRouteReturnsTheFullDecisionPayload() throws Exception {
        seedPlanWithQuantity("tprev-plan-10", "tprev.route.count", 100L);
        accountService.create(new AccountCreateRequest("tprev_acct_10", null));
        var grant = overrideService.create("tprev_acct_10", new OverrideCreateRequest("tprev.route.count", "GRANT",
            new ValueDto("QUANTITY", null, 500L, null, null, null), "route payload probe"));

        mockMvc.perform(get("/admin/v1/accounts/{external}/overrides/{id}/removal-preview",
                "tprev_acct_10", grant.overrideId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.account").value("tprev_acct_10"))
            .andExpect(jsonPath("$.capability").value("tprev.route.count"))
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.value.type").value("QUANTITY"))
            .andExpect(jsonPath("$.value.amount").value(100))
            .andExpect(jsonPath("$.snapshotVersion").isNumber())
            .andExpect(jsonPath("$.evaluatedAt").isString())
            .andExpect(jsonPath("$.trace.baseline.source").value("PLAN"))
            .andExpect(jsonPath("$.trace.result.value.amount").value(100))
            // /admin/v1 is out of scope for the snapshot-version header (contracts/README.md);
            // only /admin/v1/check carries it, because it echoes the /v1 payload byte for byte.
            .andExpect(header().doesNotExist("X-Entitlement-Snapshot-Version"));
    }

    private void seedPlanWithQuantity(String planKey, String capabilityKey, long planAmount) {
        planService.create(new PlanCreateRequest(planKey, planKey, null));
        planService.designateDefault(planKey);
        capabilityService.create(new CapabilityCreateRequest(capabilityKey, capabilityKey, null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        var edit = new PlanEntitlementEditRequest(
            Map.of(capabilityKey, new ValueDto("QUANTITY", null, planAmount, null, null, null)), List.of(), null, null);
        planService.apply(planKey, new PlanEntitlementEditRequest(edit.set(), edit.unset(), null,
            planService.preview(planKey, edit).previewToken()));
    }
}
