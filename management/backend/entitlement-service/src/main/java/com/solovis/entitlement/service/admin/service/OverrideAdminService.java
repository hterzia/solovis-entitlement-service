package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideMutationResponseDto;
import com.solovis.entitlement.service.api.DecisionMapper;
import com.solovis.entitlement.service.api.dto.DecisionResponseDto;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.audit.AuditSource;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.error.RefId;
import com.solovis.entitlement.service.snapshot.*;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import com.solovis.entitlement.service.time.Timestamps;
import java.util.LinkedHashMap;
import java.util.Optional;

@Service
public class OverrideAdminService {

    private final AccountRepository accountRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final CapabilityRepository capabilityRepository;
    private final AuditRecorder auditRecorder;
    private final AuditJson auditJson;
    private final ActorResolver actorResolver;
    private final AuditSource auditSource;
    private final SnapshotPublisher snapshotPublisher;
    private final RecordViewAssembler recordViewAssembler;
    private final Clock clock;

    public OverrideAdminService(AccountRepository accountRepository, AccountOverrideRepository accountOverrideRepository,
            CapabilityRepository capabilityRepository, AuditRecorder auditRecorder, AuditJson auditJson, ActorResolver actorResolver,
            AuditSource auditSource, SnapshotPublisher snapshotPublisher, RecordViewAssembler recordViewAssembler,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.capabilityRepository = capabilityRepository;
        this.auditRecorder = auditRecorder;
        this.auditJson = auditJson;
        this.actorResolver = actorResolver;
        this.auditSource = auditSource;
        this.snapshotPublisher = snapshotPublisher;
        this.recordViewAssembler = recordViewAssembler;
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

        String now = Timestamps.iso(clock.instant());
        var actor = actorResolver.currentActor();
        var columns = ValueColumnCodec.toColumns(value);
        long id = accountOverrideRepository.insert(new AccountOverrideRow(null, accountRow.id(), capRow.id(), kind.name(),
            columns.boolValue(), columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(), request.reason(),
            now, actor.id(), actor.kind().name(), null, null, null));
        var override = new AccountOverride(java.util.OptionalLong.of(id), external, capability.key(), kind, value,
            Optional.of(request.reason()), Optional.of(actor.id()), Optional.of(java.time.Instant.parse(now)));

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source(auditSource.current()).entityType("OVERRIDE")
            .entityId("ovr_" + id).action("CREATE").accountId(accountRow.id()).capabilityId(capRow.id())
            .reason(request.reason()).afterJson(auditJson.write(request)).build());
        long newVersion = snapshotPublisher.publish(auditSeq,
            new DeltaChange.OverrideCreated("ovr_" + id, external, capability.key().value(), kind.name(), ValueMapper.toDto(value)));

        var explanation = Resolver.explain(
            recordViewAssembler.pointViewInWriteTxn(external, capability.key().value()),
            external, capability.key(), clock.instant());
        return new OverrideMutationResponseDto("ovr_" + id, DecisionMapper.toResponse(explanation, capability), newVersion, 60);
    }

    /**
     * What the answer becomes if this override were removed — the confirmation screen 3 shows before
     * the operator commits (ui-screens.md, c14/c15). It is a read: no soft-delete, no audit event, no
     * published version. The hypothetical is the record-backed view of this account and capability
     * minus this one override ({@link RecordBackedView#withoutOverride}), answered by the same
     * {@link Resolver#explain} the real removal uses, so a preview and the removal that follows it
     * cannot disagree.
     *
     * <p>Read-transactional for the same reason every other read is: the four lookups behind the view
     * must describe one moment, or the preview could mix states (c31).
     *
     * <p>The version stays at the current one rather than {@code +1}: nothing was published, so
     * "as of version N" is the only honest thing the payload can say.
     */
    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public DecisionResponseDto previewRemoval(String external, String overrideRef) {
        // Account first, so an unknown account is answered as such (§6.3) rather than as a bad ref.
        var accountRow = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        long id = RefId.parse(overrideRef, "ovr_");
        var overrideRow = accountOverrideRepository.findById(id)
            .filter(row -> row.accountId() == accountRow.id())
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + overrideRef + "'."));
        if (overrideRow.removedAt() != null) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Override '" + overrideRef + "' is already removed.");
        }
        var capRow = capabilityRepository.findById(overrideRow.capabilityId()).orElseThrow();
        var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));

        var without = recordViewAssembler.pointView(external, capability.key().value()).withoutOverride(id);
        var explanation = Resolver.explain(without, external, capability.key(), clock.instant());
        return DecisionMapper.toResponse(explanation, capability);
    }

    @Transactional
    public OverrideMutationResponseDto delete(String external, String overrideRef, String removeReason) {
        long id = RefId.parse(overrideRef, "ovr_");
        String canonicalRef = "ovr_" + id;
        var overrideRow = accountOverrideRepository.findById(id)
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + overrideRef + "'."));
        var accountRow = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        if (overrideRow.accountId() != accountRow.id()) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + overrideRef + "'.");
        }
        var capRow = capabilityRepository.findById(overrideRow.capabilityId()).orElseThrow();
        var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
        var capabilityKey = capability.key();

        // c32: capture what is about to be removed before the soft-delete write, so the REMOVE
        // audit event carries a before-snapshot (not just the removal reason).
        var decodedValue = ValueColumnCodec.toValue(capability.valueType(), overrideRow.boolValue(), overrideRow.qtyValue(),
            overrideRow.qtyUnlimited(), overrideRow.tierValue(), capability.tierOrder());
        var beforeMap = new LinkedHashMap<String, Object>();
        beforeMap.put("capability", capabilityKey.value());
        beforeMap.put("kind", overrideRow.kind());
        beforeMap.put("value", ValueMapper.toDto(decodedValue));
        beforeMap.put("reason", overrideRow.reason());
        beforeMap.put("createdBy", overrideRow.createdBy());
        beforeMap.put("createdAt", overrideRow.createdAt());

        String now = Timestamps.iso(clock.instant());
        var actor = actorResolver.currentActor();
        boolean removed = accountOverrideRepository.remove(id, now, actor.id(), removeReason);
        if (!removed) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Override '" + overrideRef + "' is already removed.");
        }

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source(auditSource.current()).entityType("OVERRIDE")
            .entityId(canonicalRef).action("REMOVE").accountId(accountRow.id()).capabilityId(capRow.id())
            .reason(removeReason).beforeJson(auditJson.write(beforeMap)).build());
        long newVersion = snapshotPublisher.publish(auditSeq, new DeltaChange.OverrideRemoved(canonicalRef));

        // A retired capability's referent still needs its stale overrides cleared (spec §3.4) —
        // but Resolver.lookUp/explain refuses to evaluate a retired capability at all, so the
        // response carries no decision in that case rather than rolling back the removal.
        if (capability.isRetired()) {
            return new OverrideMutationResponseDto(canonicalRef, null, newVersion, 60);
        }
        // The write connection sees this transaction's own uncommitted soft-delete, so the
        // explanation already describes the post-removal answer.
        var explanation = Resolver.explain(
            recordViewAssembler.pointViewInWriteTxn(external, capabilityKey.value()),
            external, capabilityKey, clock.instant());
        return new OverrideMutationResponseDto(canonicalRef, DecisionMapper.toResponse(explanation, capability), newVersion, 60);
    }
}
