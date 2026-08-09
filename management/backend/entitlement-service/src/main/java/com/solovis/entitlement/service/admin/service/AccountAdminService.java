package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Explanation;
import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.api.DecisionMapper;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.*;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class AccountAdminService {

    private final AccountRepository accountRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final PlanRepository planRepository;
    private final CapabilityRepository capabilityRepository;
    private final AuditRecorder auditRecorder;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public AccountAdminService(AccountRepository accountRepository, AccountOverrideRepository accountOverrideRepository,
            PlanRepository planRepository, CapabilityRepository capabilityRepository, AuditRecorder auditRecorder,
            ActorResolver actorResolver, SnapshotPublisher snapshotPublisher, SnapshotHolder snapshotHolder, Clock clock) {
        this.accountRepository = accountRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.planRepository = planRepository;
        this.capabilityRepository = capabilityRepository;
        this.auditRecorder = auditRecorder;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    public List<AccountSummaryDto> search(String q, String planKey, long afterId, int limit) {
        Long planId = planKey == null ? null : planRepository.findByKey(planKey).map(PlanRow::id).orElse(-1L);
        return accountRepository.search(q, planId, afterId, limit).stream()
            .map(row -> new AccountSummaryDto(row.externalId(), row.name(),
                planRepository.findById(row.planId()).map(PlanRow::key).orElseThrow(), row.status()))
            .toList();
    }

    @Transactional
    public AccountSummaryDto create(AccountCreateRequest request) {
        if (accountRepository.existsByExternalId(request.externalId())) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Account '" + request.externalId() + "' already exists.");
        }
        var defaultPlan = planRepository.findDefault()
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.DEFAULT_PLAN_REQUIRED, "No default plan is designated."));
        String now = clock.instant().toString();
        var actor = actorResolver.currentActor();
        accountRepository.insert(new AccountRow(null, request.externalId(), request.name(), defaultPlan.id(), now,
            actor.kind().name(), actor.id(), "ACTIVE", now, now));
        var assignment = new AccountAssignment(request.externalId(), defaultPlan.key());

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source("UI").entityType("ACCOUNT")
            .entityId(request.externalId()).action("CREATE").build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withAccount(base, v, assignment), auditSeq,
            new DeltaChange.AccountUpserted(request.externalId(), defaultPlan.key()));

        return new AccountSummaryDto(request.externalId(), request.name(), defaultPlan.key(), "ACTIVE");
    }

    public AccountDetailDto get(String external) {
        var row = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        var planRow = planRepository.findById(row.planId()).orElseThrow();
        Snapshot snapshot = snapshotHolder.current();

        Map<Long, Explanation> explanationsByCapabilityId = new HashMap<>();
        var entitlements = new ArrayList<AccountDetailDto.EntitlementRow>();
        for (var capability : snapshot.activeCapabilities()) {
            var explanation = Resolver.explain(snapshot, external, capability.key(), clock.instant());
            var capRow = capabilityRepository.findByKey(capability.key().value()).orElseThrow();
            explanationsByCapabilityId.put(capRow.id(), explanation);
            var trace = explanation.trace();
            String source; AccountDetailDto.SourceDetail detail;
            if (trace.holdWinner().isPresent()) {
                source = "HOLD";
                detail = new AccountDetailDto.SourceDetail("ovr_" + trace.holdWinner().get().overrideId().getAsLong(),
                    trace.holdWinner().get().reason().orElse(null), null);
            } else if (trace.grantWinner().isPresent()) {
                source = "GRANT";
                detail = new AccountDetailDto.SourceDetail("ovr_" + trace.grantWinner().get().overrideId().getAsLong(),
                    trace.grantWinner().get().reason().orElse(null), null);
            } else if (trace.baseline().source() == com.solovis.entitlement.core.engine.TraceSource.PLAN) {
                source = "PLAN";
                detail = new AccountDetailDto.SourceDetail(null, null, trace.baseline().planKey().orElse(null));
            } else {
                source = "CAPABILITY_DEFAULT";
                detail = null;
            }
            entitlements.add(new AccountDetailDto.EntitlementRow(capability.key().value(), capability.area(),
                explanation.decision().allowed(), ValueMapper.toDto(explanation.decision().value()), source, detail));
        }

        var overrides = new ArrayList<AccountDetailDto.OverrideRow>();
        for (var overrideRow : accountOverrideRepository.findLiveForAccount(row.id())) {
            var capRow = capabilityRepository.findById(overrideRow.capabilityId()).orElseThrow();
            var explanation = explanationsByCapabilityId.get(overrideRow.capabilityId());
            String effectNow = explanation == null ? null : effectNow(overrideRow, explanation.trace());
            var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
            var value = ValueColumnCodec.toValue(capability.valueType(), overrideRow.boolValue(), overrideRow.qtyValue(),
                overrideRow.qtyUnlimited(), overrideRow.tierValue(), capability.tierOrder());
            overrides.add(new AccountDetailDto.OverrideRow("ovr_" + overrideRow.id(), capRow.key(), overrideRow.kind(),
                ValueMapper.toDto(value), overrideRow.reason(), overrideRow.createdBy(), overrideRow.createdAt(), effectNow));
        }

        return new AccountDetailDto(external, row.name(), row.status(),
            new AccountDetailDto.PlanInfo(planRow.key(), planRow.name(), row.planAssignedAt(), row.planAssignmentActor(), row.planAssignmentSource()),
            snapshot.snapshotVersion(), entitlements, overrides);
    }

    /** Derives the one effectNow value data-model.md's override list needs, from the same Trace the entitlements row already computed — see this task's documented limitation for the retired-capability case (handled by the caller passing a null Explanation). */
    private static String effectNow(AccountOverrideRow row, com.solovis.entitlement.core.engine.Trace trace) {
        boolean isGrant = row.kind().equals("GRANT");
        var candidates = isGrant ? trace.grants() : trace.holds();
        var winner = isGrant ? trace.grantWinner() : trace.holdWinner();
        boolean isWinner = winner.isPresent() && winner.get().overrideId().equals(java.util.OptionalLong.of(row.id()));
        boolean groupApplied = winner.isPresent();
        if (isWinner) {
            if (isGrant) {
                return groupApplied ? (trace.holdWinner().isPresent() ? "OVERRIDDEN_BY_HOLD" : "WINNING") : "NO_EFFECT_PLAN_MORE_GENEROUS";
            }
            return groupApplied ? "WINNING" : "NO_EFFECT_NOT_MORE_RESTRICTIVE";
        }
        boolean isCandidate = candidates.stream().anyMatch(c -> c.overrideId().equals(java.util.OptionalLong.of(row.id())));
        if (!isCandidate) {
            return null; // not among this capability's candidates at all — shouldn't happen for a live override, defensive only
        }
        return isGrant ? "SUPERSEDED_BY_GRANT" : "SUPERSEDED_BY_STRICTER_HOLD";
    }

    @Transactional
    public PlanReassignResponseDto reassignPlan(String external, PlanReassignRequest request) {
        var row = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        var targetPlan = planRepository.findByKey(request.planKey())
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No plan with key '" + request.planKey() + "'."));
        if (!targetPlan.status().equals("ACTIVE")) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Plan '" + request.planKey() + "' is not ACTIVE.");
        }
        String source = request.source() != null ? request.source() : actorResolver.currentActor().kind().name();
        String actorId = request.actor() != null ? request.actor() : actorResolver.currentActor().id();
        String now = clock.instant().toString();
        accountRepository.updatePlanAssignment(row.id(), targetPlan.id(), now, source, actorId, now);
        var assignment = new AccountAssignment(external, targetPlan.key());

        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(new com.solovis.entitlement.service.audit.Actor(actorId,
                com.solovis.entitlement.service.audit.Actor.Kind.valueOf(source)))
            .source("UI").entityType("ACCOUNT_PLAN").entityId(external).action("ASSIGN").accountId(row.id())
            .planId(targetPlan.id()).reason(request.reason())
            .beforeJson(AuditJson.write(Map.of("planKey", planRepository.findById(row.planId()).map(PlanRow::key).orElse(null))))
            .afterJson(AuditJson.write(Map.of("planKey", targetPlan.key()))).build());
        long newVersion = snapshotPublisher.publish((base, v) -> SnapshotMutator.withAccount(base, v, assignment), auditSeq,
            new DeltaChange.AccountUpserted(external, targetPlan.key()));

        long retained = accountOverrideRepository.findLiveForAccount(row.id()).size();
        return new PlanReassignResponseDto(external, targetPlan.key(), retained, newVersion);
    }
}
