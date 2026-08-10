package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.DeltaChange;
import com.solovis.entitlement.service.snapshot.SnapshotPublisher;
import com.solovis.entitlement.service.snapshot.ValueColumnCodec;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class CapabilityAdminService {

    private final CapabilityRepository capabilityRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final PlanRepository planRepository;
    private final AuditRecorder auditRecorder;
    private final AuditJson auditJson;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final Clock clock;

    public CapabilityAdminService(CapabilityRepository capabilityRepository, PlanEntitlementRepository planEntitlementRepository,
            AccountOverrideRepository accountOverrideRepository, PlanRepository planRepository, AuditRecorder auditRecorder,
            AuditJson auditJson, ActorResolver actorResolver, SnapshotPublisher snapshotPublisher, Clock clock) {
        this.capabilityRepository = capabilityRepository;
        this.planEntitlementRepository = planEntitlementRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.planRepository = planRepository;
        this.auditRecorder = auditRecorder;
        this.auditJson = auditJson;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.clock = clock;
    }

    public List<CapabilityDescriptorDto> list(String area, String status, String q) {
        String sqlStatus = (status == null || status.equals("ALL")) ? null : status;
        return capabilityRepository.findAll(area, sqlStatus, q).stream()
            .map(row -> com.solovis.entitlement.service.snapshot.RowMappers.toCapability(row, capabilityRepository.findTiers(row.id())))
            .map(CapabilityDescriptorMapper::toDescriptor)
            .toList();
    }

    public CapabilityDescriptorDto get(String key) {
        return CapabilityDescriptorMapper.toDescriptor(loadDomain(key));
    }

    @Transactional
    public CapabilityDescriptorDto create(CapabilityCreateRequest request) {
        if (capabilityRepository.existsByKey(request.key())) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
                "Capability key '" + request.key() + "' is already declared.");
        }
        ValueType valueType = parseValueType(request.valueType());
        if (!request.defaultValue().type().equals(valueType.name())) {
            throw new EntitlementApiException(ErrorCode.VALUE_TYPE_MISMATCH,
                "Default value type " + request.defaultValue().type() + " does not match declared type " + valueType + ".");
        }

        TierOrder tierOrder = buildTierOrder(request.tiers());
        EntitlementValue defaultValue = decode(request.defaultValue(), valueType, tierOrder);
        Optional<OffValue> offValue = request.offValue() == null
            ? Optional.empty() : Optional.of(new OffValue(decode(request.offValue(), valueType, tierOrder)));

        Capability capability = buildCapability(new CapabilityKey(request.key()), request.displayName(),
            request.description(), valueType, defaultValue, offValue, tierOrder, Capability.Status.ACTIVE, null);

        String now = clock.instant().toString();
        var columns = ValueColumnCodec.toColumns(defaultValue);
        var offColumns = offValue.map(ov -> ValueColumnCodec.toColumns(ov.value()));
        long id = capabilityRepository.insert(new CapabilityRow(null, capability.key().value(), capability.area(),
            capability.displayName(), capability.description(), valueType.name(),
            columns.boolValue(), columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(),
            offValue.isPresent(), offColumns.map(ValueColumnCodec.Columns::qtyValue).orElse(null),
            offColumns.map(ValueColumnCodec.Columns::tierValue).orElse(null), "ACTIVE", null, now, now));
        for (var tier : tierOrder.tiers()) {
            capabilityRepository.insertTier(new CapabilityTierRow(id, tier.tierKey(), tier.ordinal(), tier.displayName()));
        }

        var descriptor = CapabilityDescriptorMapper.toDescriptor(capability);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY")
            .entityId(capability.key().value()).action("CREATE").capabilityId(id)
            .afterJson(auditJson.write(descriptor)).build());

        snapshotPublisher.publish(auditSeq, new DeltaChange.CapabilityUpserted(descriptor));

        return descriptor;
    }

    @Transactional
    public CapabilityDescriptorDto patch(String key, CapabilityPatchRequest request) {
        Capability current = loadDomain(key);
        var row = capabilityRepository.findByKey(key).orElseThrow();

        String displayName = request.displayName() != null ? request.displayName() : current.displayName();
        String description = request.description() != null ? request.description() : current.description();
        EntitlementValue defaultValue = request.defaultValue() != null
            ? ValueMapper.fromDto(request.defaultValue(), current) : current.defaultValue();
        Optional<OffValue> offValue = request.offValue() != null
            ? Optional.of(new OffValue(ValueMapper.fromDto(request.offValue(), current))) : current.offValue();

        Capability updated = buildCapability(current.key(), displayName, description, current.valueType(),
            defaultValue, offValue, current.tierOrder(), current.status(), current.retiredAt());

        String now = clock.instant().toString();
        var columns = ValueColumnCodec.toColumns(defaultValue);
        var offColumns = offValue.map(ov -> ValueColumnCodec.toColumns(ov.value()));
        capabilityRepository.update(new CapabilityRow(row.id(), row.key(), row.area(), displayName, description,
            row.valueType(), columns.boolValue(), columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(),
            offValue.isPresent(), offColumns.map(ValueColumnCodec.Columns::qtyValue).orElse(null),
            offColumns.map(ValueColumnCodec.Columns::tierValue).orElse(null), row.status(), row.retiredAt(),
            row.createdAt(), now));

        var descriptor = CapabilityDescriptorMapper.toDescriptor(updated);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY")
            .entityId(key).action("UPDATE").capabilityId(row.id())
            .beforeJson(auditJson.write(CapabilityDescriptorMapper.toDescriptor(current)))
            .afterJson(auditJson.write(descriptor)).build());

        snapshotPublisher.publish(auditSeq, new DeltaChange.CapabilityUpserted(descriptor));
        return descriptor;
    }

    @Transactional
    public CapabilityDescriptorDto appendTier(String key, TierAppendRequest request) {
        Capability current = loadDomain(key);
        if (current.valueType() != ValueType.TIER) {
            throw new EntitlementApiException(ErrorCode.IMMUTABLE_FIELD, "Capability '" + key + "' is not a TIER capability.");
        }
        if (current.tierOrder().declares(request.tier())) {
            throw new EntitlementApiException(ErrorCode.IMMUTABLE_FIELD,
                "Tier '" + request.tier() + "' already exists; tiers may only be appended (data-model.md).");
        }
        TierOrder appended = current.tierOrder().appending(request.tier(), request.displayName());
        Capability updated = buildCapability(current.key(), current.displayName(), current.description(),
            current.valueType(), current.defaultValue(), current.offValue(), appended, current.status(), current.retiredAt());

        var row = capabilityRepository.findByKey(key).orElseThrow();
        int newOrdinal = appended.maxOrdinal();
        capabilityRepository.insertTier(new CapabilityTierRow(row.id(), request.tier(), newOrdinal, request.displayName()));

        var descriptor = CapabilityDescriptorMapper.toDescriptor(updated);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY_TIER")
            .entityId(key).action("CREATE").capabilityId(row.id())
            .afterJson(auditJson.write(descriptor)).build());

        snapshotPublisher.publish(auditSeq, new DeltaChange.CapabilityUpserted(descriptor));
        return descriptor;
    }

    @Transactional
    public CapabilityRetireResponseDto retire(String key) {
        Capability current = loadDomain(key);
        if (current.isRetired()) {
            throw new EntitlementApiException(ErrorCode.RETIRED_CAPABILITY, "Capability '" + key + "' is already retired.");
        }
        var row = capabilityRepository.findByKey(key).orElseThrow();
        String now = clock.instant().toString();
        boolean retired = capabilityRepository.retire(row.id(), now, now);
        if (!retired) {
            throw new EntitlementApiException(ErrorCode.RETIRED_CAPABILITY, "Capability '" + key + "' is already retired.");
        }
        Capability updated = buildCapability(current.key(), current.displayName(), current.description(),
            current.valueType(), current.defaultValue(), current.offValue(), current.tierOrder(),
            Capability.Status.RETIRED, java.time.Instant.parse(now));

        var planKeys = planEntitlementRepository.findPlanIdsUsingCapability(row.id()).stream()
            .map(planId -> planRepository.findById(planId).orElseThrow().key()).toList();
        long liveOverrides = accountOverrideRepository.countLiveForCapability(row.id());

        var descriptor = CapabilityDescriptorMapper.toDescriptor(updated);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY")
            .entityId(key).action("RETIRE").capabilityId(row.id())
            .afterJson(auditJson.write(descriptor)).build());

        snapshotPublisher.publish(auditSeq, new DeltaChange.CapabilityRetired(key));

        return new CapabilityRetireResponseDto(descriptor, new CapabilityRetireResponseDto.Usage(planKeys, liveOverrides));
    }

    private Capability loadDomain(String key) {
        var row = capabilityRepository.findByKey(key)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(key));
        return com.solovis.entitlement.service.snapshot.RowMappers.toCapability(row, capabilityRepository.findTiers(row.id()));
    }

    private static ValueType parseValueType(String raw) {
        try {
            return ValueType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Unknown value type '" + raw + "'.");
        }
    }

    private static TierOrder buildTierOrder(List<CapabilityCreateRequest.TierRequest> tiers) {
        if (tiers == null) {
            return TierOrder.NONE;
        }
        var definitions = new ArrayList<TierOrder.TierDefinition>();
        for (int i = 0; i < tiers.size(); i++) {
            definitions.add(new TierOrder.TierDefinition(tiers.get(i).tier(), i, tiers.get(i).displayName()));
        }
        try {
            return new TierOrder(definitions);
        } catch (IllegalArgumentException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private static EntitlementValue decode(com.solovis.entitlement.service.dto.ValueDto dto, ValueType valueType, TierOrder tierOrder) {
        if (valueType == ValueType.TIER && tierOrder.tiers().isEmpty()) {
            // Guarded ahead of the shell build below: an empty tier list would otherwise reach
            // tierOrder.tiers().get(0) and throw IndexOutOfBoundsException, which isn't caught by
            // the IllegalArgumentException handler that Capability's own validation raises.
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "A TIER capability must declare at least two tiers.");
        }
        // A minimal capability shell is enough for ValueMapper.fromDto — it only reads valueType() and tierOrder().
        // Building it still runs Capability's own invariants (e.g. "a TIER capability must declare at least two
        // tiers"), so a malformed tierOrder surfaces here as the same domain error the real capability would raise.
        Capability shell;
        try {
            shell = new Capability(new CapabilityKey("shell.value"), "shell", null, valueType,
                valueType == ValueType.TIER ? new EntitlementValue.Tier(tierOrder.tiers().get(0).tierKey(), 0)
                    : valueType == ValueType.QUANTITY ? EntitlementValue.Quantity.of(0) : new EntitlementValue.Switch(false),
                Optional.empty(), tierOrder, Capability.Status.ACTIVE, null);
        } catch (IllegalArgumentException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        return ValueMapper.fromDto(dto, shell);
    }

    private static Capability buildCapability(CapabilityKey key, String displayName, String description,
            ValueType valueType, EntitlementValue defaultValue, Optional<OffValue> offValue, TierOrder tierOrder,
            Capability.Status status, java.time.Instant retiredAt) {
        try {
            return new Capability(key, displayName, description, valueType, defaultValue, offValue, tierOrder, status, retiredAt);
        } catch (IllegalArgumentException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}
