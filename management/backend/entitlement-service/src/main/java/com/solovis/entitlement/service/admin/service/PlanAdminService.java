package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.admin.PreviewTokenCodec;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.api.DecisionMapper;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.ValueDto;
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
public class PlanAdminService {

    private final PlanRepository planRepository;
    private final CapabilityRepository capabilityRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final AuditRecorder auditRecorder;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public PlanAdminService(PlanRepository planRepository, CapabilityRepository capabilityRepository,
            PlanEntitlementRepository planEntitlementRepository, AuditRecorder auditRecorder, ActorResolver actorResolver,
            SnapshotPublisher snapshotPublisher, SnapshotHolder snapshotHolder, Clock clock) {
        this.planRepository = planRepository;
        this.capabilityRepository = capabilityRepository;
        this.planEntitlementRepository = planEntitlementRepository;
        this.auditRecorder = auditRecorder;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    public List<PlanSummaryDto> list() {
        return planRepository.findAll(null).stream().map(row -> new PlanSummaryDto(row.key(), row.name(), row.status(),
            row.defaultForNewAccounts(), planRepository.countAccounts(row.id()),
            planEntitlementRepository.findByPlan(row.id()).size())).toList();
    }

    public PlanDetailDto get(String key) {
        var row = requireRow(key);
        var capabilitiesById = capabilityRepository.findAll(null, null, null).stream()
            .collect(java.util.stream.Collectors.toMap(CapabilityRow::id, r -> r));
        Map<String, ValueDto> entitlements = new LinkedHashMap<>();
        for (var pe : planEntitlementRepository.findByPlan(row.id())) {
            var capRow = capabilitiesById.get(pe.capabilityId());
            var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
            entitlements.put(capability.key().value(), ValueMapper.toDto(RowMappers.toPlanEntitlement(pe, key, capability).value()));
        }
        return new PlanDetailDto(row.key(), row.name(), row.description(), row.status(), row.defaultForNewAccounts(),
            planRepository.countAccounts(row.id()), entitlements);
    }

    @Transactional
    public PlanSummaryDto create(PlanCreateRequest request) {
        if (planRepository.findByKey(request.key()).isPresent()) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Plan key '" + request.key() + "' is already declared.");
        }
        String now = clock.instant().toString();
        planRepository.insert(new PlanRow(null, request.key(), request.name(), request.description(), "ACTIVE", false, now, now));
        var plan = new Plan(request.key(), request.name(), Plan.Status.ACTIVE, false);

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN").entityId(request.key()).action("CREATE").planId(requireRow(request.key()).id())
            .afterJson(AuditJson.write(request)).build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withPlan(base, v, plan), auditSeq,
            new DeltaChange.PlanUpserted(plan.key(), plan.name(), "ACTIVE", false));

        return new PlanSummaryDto(request.key(), request.name(), "ACTIVE", false, 0, 0);
    }

    @Transactional
    public PlanSummaryDto patch(String key, PlanPatchRequest request) {
        var row = requireRow(key);
        String name = request.name() != null ? request.name() : row.name();
        String description = request.description() != null ? request.description() : row.description();
        String now = clock.instant().toString();
        planRepository.update(row.id(), name, description, now);
        var plan = new Plan(key, name, Plan.Status.valueOf(row.status()), row.defaultForNewAccounts());

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN").entityId(key).action("UPDATE").planId(row.id())
            .beforeJson(AuditJson.write(Map.of("name", row.name()))).afterJson(AuditJson.write(Map.of("name", name))).build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withPlan(base, v, plan), auditSeq,
            new DeltaChange.PlanUpserted(key, name, row.status(), row.defaultForNewAccounts()));

        return new PlanSummaryDto(key, name, row.status(), row.defaultForNewAccounts(), planRepository.countAccounts(row.id()),
            planEntitlementRepository.findByPlan(row.id()).size());
    }

    public PlanPreviewResponseDto preview(String key, PlanEntitlementEditRequest request) {
        var row = requireRow(key);
        Snapshot snapshot = snapshotHolder.current();
        var diffs = new ArrayList<PlanPreviewResponseDto.Diff>();
        Map<String, String> canonicalSet = new LinkedHashMap<>();
        for (var entry : request.set().entrySet()) {
            var capability = requireDomainCapability(entry.getKey());
            var newValue = ValueMapper.fromDto(entry.getValue(), capability);
            var before = snapshot.planEntitlement(key, capability.key()).map(pe -> ValueMapper.toDto(pe.value())).orElse(null);
            diffs.add(new PlanPreviewResponseDto.Diff(entry.getKey(), before, ValueMapper.toDto(newValue), null));
            canonicalSet.put(entry.getKey(), newValue.valueType() + ":" + newValue);
        }
        for (var capabilityKey : request.unset()) {
            var capability = requireDomainCapability(capabilityKey);
            var before = snapshot.planEntitlement(key, capability.key()).map(pe -> ValueMapper.toDto(pe.value())).orElse(null);
            diffs.add(new PlanPreviewResponseDto.Diff(capabilityKey, before,
                ValueMapper.toDto(capability.defaultValue()), "Falls back to the capability default."));
        }

        PlanPreviewResponseDto.PreviewAccount previewAccount = null;
        if (request.previewAccount() != null) {
            Snapshot hypothetical = applyEdit(snapshot, key, request, snapshot.snapshotVersion());
            var effects = new ArrayList<PlanPreviewResponseDto.Effect>();
            for (var diff : diffs) {
                var capKey = new CapabilityKey(diff.capability());
                var beforeExplanation = Resolver.explain(snapshot, request.previewAccount(), capKey, clock.instant());
                var afterExplanation = Resolver.explain(hypothetical, request.previewAccount(), capKey, clock.instant());
                var capability = requireDomainCapability(diff.capability());
                var beforeDto = DecisionMapper.toResponse(beforeExplanation, capability);
                var afterDto = DecisionMapper.toResponse(afterExplanation, capability);
                boolean changed = !beforeDto.value().equals(afterDto.value());
                effects.add(new PlanPreviewResponseDto.Effect(diff.capability(), beforeDto, afterDto, changed,
                    changed ? null : "No change for this account."));
            }
            previewAccount = new PlanPreviewResponseDto.PreviewAccount(request.previewAccount(), effects);
        }

        long affected = planRepository.countAccounts(row.id());
        String token = PreviewTokenCodec.compute(key, canonicalSet, request.unset(), snapshot.snapshotVersion());
        return new PlanPreviewResponseDto(key, affected, diffs, previewAccount, token);
    }

    @Transactional
    public PlanApplyResponseDto apply(String key, PlanEntitlementEditRequest request) {
        var row = requireRow(key);
        Snapshot before = snapshotHolder.current();
        Map<String, String> canonicalSet = new LinkedHashMap<>();
        Map<String, ValueDto> setDtos = new LinkedHashMap<>();
        for (var entry : request.set().entrySet()) {
            var capability = requireDomainCapability(entry.getKey());
            if (capability.isRetired()) {
                throw new EntitlementApiException(ErrorCode.CAPABILITY_RETIRED_FOR_WRITE,
                    "Capability '" + entry.getKey() + "' is retired.");
            }
            var value = ValueMapper.fromDto(entry.getValue(), capability);
            canonicalSet.put(entry.getKey(), value.valueType() + ":" + value);
            setDtos.put(entry.getKey(), ValueMapper.toDto(value));
        }
        String expectedToken = PreviewTokenCodec.compute(key, canonicalSet, request.unset(), before.snapshotVersion());
        if (request.previewToken() == null || !request.previewToken().equals(expectedToken)) {
            throw new EntitlementApiException(ErrorCode.PREVIEW_TOKEN_INVALID,
                "The preview token is missing or was computed against a different snapshot version.");
        }

        String now = clock.instant().toString();
        for (var entry : request.set().entrySet()) {
            var capRow = capabilityRepository.findByKey(entry.getKey()).orElseThrow();
            var capability = requireDomainCapability(entry.getKey());
            var value = ValueMapper.fromDto(entry.getValue(), capability);
            var columns = ValueColumnCodec.toColumns(value);
            planEntitlementRepository.upsert(new PlanEntitlementRow(row.id(), capRow.id(), columns.boolValue(),
                columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(), now));
        }
        for (var capabilityKey : request.unset()) {
            var capRow = capabilityRepository.findByKey(capabilityKey).orElseThrow();
            planEntitlementRepository.delete(row.id(), capRow.id());
        }

        long affected = planRepository.countAccounts(row.id());
        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN_ENTITLEMENT").entityId(key).action("UPDATE").planId(row.id())
            .afterJson(AuditJson.write(setDtos)).affectedAccountCount(affected).build());

        long newVersion = snapshotPublisher.publish((base, v) -> {
            Snapshot next = base;
            for (var entry : request.set().entrySet()) {
                var capability = requireDomainCapability(entry.getKey());
                var value = ValueMapper.fromDto(entry.getValue(), capability);
                next = SnapshotMutator.withPlanEntitlement(next, v, new PlanEntitlement(key, capability.key(), value));
            }
            for (var capabilityKey : request.unset()) {
                next = SnapshotMutator.withPlanEntitlementRemoved(next, v, key, new CapabilityKey(capabilityKey));
            }
            return next;
        }, auditSeq, new DeltaChange.PlanEntitlements(key, setDtos, request.unset()));

        return new PlanApplyResponseDto(key, affected, newVersion, auditSeq, 60);
    }

    @Transactional
    public void archive(String key) {
        var row = requireRow(key);
        if (planRepository.countAccounts(row.id()) > 0) {
            throw new EntitlementApiException(ErrorCode.PLAN_IN_USE, "Plan '" + key + "' still has accounts assigned.");
        }
        if (row.defaultForNewAccounts()) {
            throw new EntitlementApiException(ErrorCode.DEFAULT_PLAN_REQUIRED, "Plan '" + key + "' is the default for new accounts.");
        }
        String now = clock.instant().toString();
        planRepository.archive(row.id(), now);
        var plan = new Plan(key, row.name(), Plan.Status.ARCHIVED, false);

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN").entityId(key).action("ARCHIVE").planId(row.id()).build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withPlan(base, v, plan), auditSeq, new DeltaChange.PlanArchived(key));
    }

    @Transactional
    public void designateDefault(String key) {
        var row = requireRow(key);
        if (!row.status().equals("ACTIVE")) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Plan '" + key + "' is not ACTIVE.");
        }
        String now = clock.instant().toString();
        var previousDefault = planRepository.findDefault();
        planRepository.clearDefault(now);
        planRepository.setDefault(row.id(), now);
        var newDefaultPlan = new Plan(key, row.name(), Plan.Status.ACTIVE, true);

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("DEFAULT_PLAN").entityId(key).action("DESIGNATE").planId(row.id()).build());

        snapshotPublisher.publish((base, v) -> {
            Snapshot next = SnapshotMutator.withPlan(base, v, newDefaultPlan);
            if (previousDefault.isPresent() && !previousDefault.get().key().equals(key)) {
                var old = previousDefault.get();
                next = SnapshotMutator.withPlan(next, v, new Plan(old.key(), old.name(), Plan.Status.ACTIVE, false));
            }
            return next;
        }, auditSeq, new DeltaChange.PlanDefaultChanged(key));
    }

    private Snapshot applyEdit(Snapshot base, String key, PlanEntitlementEditRequest request, long version) {
        Snapshot next = base;
        for (var entry : request.set().entrySet()) {
            var capability = requireDomainCapability(entry.getKey());
            var value = ValueMapper.fromDto(entry.getValue(), capability);
            next = SnapshotMutator.withPlanEntitlement(next, version, new PlanEntitlement(key, capability.key(), value));
        }
        for (var capabilityKey : request.unset()) {
            next = SnapshotMutator.withPlanEntitlementRemoved(next, version, key, new CapabilityKey(capabilityKey));
        }
        return next;
    }

    private PlanRow requireRow(String key) {
        return planRepository.findByKey(key)
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No plan with key '" + key + "'."));
    }

    private Capability requireDomainCapability(String key) {
        var row = capabilityRepository.findByKey(key)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(key));
        return RowMappers.toCapability(row, capabilityRepository.findTiers(row.id()));
    }
}
