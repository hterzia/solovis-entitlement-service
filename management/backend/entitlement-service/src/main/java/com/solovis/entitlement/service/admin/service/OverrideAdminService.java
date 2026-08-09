package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideMutationResponseDto;
import com.solovis.entitlement.service.api.DecisionMapper;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.error.RefId;
import com.solovis.entitlement.service.snapshot.*;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.Optional;

@Service
public class OverrideAdminService {

    private final AccountRepository accountRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final CapabilityRepository capabilityRepository;
    private final AuditRecorder auditRecorder;
    private final AuditJson auditJson;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public OverrideAdminService(AccountRepository accountRepository, AccountOverrideRepository accountOverrideRepository,
            CapabilityRepository capabilityRepository, AuditRecorder auditRecorder, AuditJson auditJson, ActorResolver actorResolver,
            SnapshotPublisher snapshotPublisher, SnapshotHolder snapshotHolder, Clock clock) {
        this.accountRepository = accountRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.capabilityRepository = capabilityRepository;
        this.auditRecorder = auditRecorder;
        this.auditJson = auditJson;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    @Transactional
    public OverrideMutationResponseDto create(String external, OverrideCreateRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new EntitlementApiException(ErrorCode.REASON_REQUIRED, "An override's reason must be non-empty.");
        }
        var accountRow = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        var capRow = capabilityRepository.findByKey(request.capability())
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(request.capability()));
        if (capRow.status().equals("RETIRED")) {
            throw new EntitlementApiException(ErrorCode.CAPABILITY_RETIRED_FOR_WRITE,
                "Capability '" + request.capability() + "' is retired.");
        }
        var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
        var value = ValueMapper.fromDto(request.value(), capability);
        OverrideKind kind;
        try { kind = OverrideKind.valueOf(request.kind()); }
        catch (IllegalArgumentException e) { throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Unknown override kind '" + request.kind() + "'."); }

        String now = clock.instant().toString();
        var actor = actorResolver.currentActor();
        var columns = ValueColumnCodec.toColumns(value);
        long id = accountOverrideRepository.insert(new AccountOverrideRow(null, accountRow.id(), capRow.id(), kind.name(),
            columns.boolValue(), columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(), request.reason(),
            now, actor.id(), actor.kind().name(), null, null, null));
        var override = new AccountOverride(java.util.OptionalLong.of(id), external, capability.key(), kind, value,
            Optional.of(request.reason()), Optional.of(actor.id()), Optional.of(java.time.Instant.parse(now)));

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source("UI").entityType("OVERRIDE")
            .entityId("ovr_" + id).action("CREATE").accountId(accountRow.id()).capabilityId(capRow.id())
            .reason(request.reason()).afterJson(auditJson.write(request)).build());
        var base = snapshotHolder.current();
        var next = SnapshotMutator.withOverrideAdded(base, base.snapshotVersion() + 1, override);
        long newVersion = snapshotPublisher.publish((b, v) -> next, auditSeq,
            new DeltaChange.OverrideCreated("ovr_" + id, external, capability.key().value(), kind.name(), ValueMapper.toDto(value)));

        var explanation = Resolver.explain(next, external, capability.key(), clock.instant());
        return new OverrideMutationResponseDto("ovr_" + id, DecisionMapper.toResponse(explanation, capability), newVersion, 60);
    }

    @Transactional
    public OverrideMutationResponseDto delete(String external, String overrideRef, String removeReason) {
        long id = RefId.parse(overrideRef, "ovr_");
        var overrideRow = accountOverrideRepository.findById(id)
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + overrideRef + "'."));
        var accountRow = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        if (overrideRow.accountId() != accountRow.id()) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + overrideRef + "'.");
        }
        var capRow = capabilityRepository.findById(overrideRow.capabilityId()).orElseThrow();

        String now = clock.instant().toString();
        var actor = actorResolver.currentActor();
        boolean removed = accountOverrideRepository.remove(id, now, actor.id(), removeReason);
        if (!removed) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Override '" + overrideRef + "' is already removed.");
        }
        var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
        var capabilityKey = capability.key();

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source("UI").entityType("OVERRIDE")
            .entityId(overrideRef).action("REMOVE").accountId(accountRow.id()).capabilityId(capRow.id())
            .reason(removeReason).build());
        var base = snapshotHolder.current();
        var next = SnapshotMutator.withOverrideRemoved(base, base.snapshotVersion() + 1, external, capabilityKey, id);
        long newVersion = snapshotPublisher.publish((b, v) -> next, auditSeq, new DeltaChange.OverrideRemoved(overrideRef));

        var explanation = Resolver.explain(next, external, capabilityKey, clock.instant());
        return new OverrideMutationResponseDto(overrideRef, DecisionMapper.toResponse(explanation, capability), newVersion, 60);
    }
}
