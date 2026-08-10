package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.service.api.dto.CapabilityListResponseDto;
import com.solovis.entitlement.service.api.dto.DecisionResponseDto;
import com.solovis.entitlement.service.api.dto.WholeAccountResponseDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.RecordViewAssembler;
import com.solovis.entitlement.service.snapshot.RowMappers;
import com.solovis.entitlement.service.store.DecisionReadDao;
import com.solovis.entitlement.service.time.Timestamps;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The transaction boundary for every {@code /v1} read. Each public method opens one read-pool
 * transaction, assembles a {@code RecordBackedView} (or reads {@link DecisionReadDao} directly for
 * the account-agnostic registry routes) and resolves against it, so the version reported in the
 * response and the model it was computed from always describe the same moment.
 */
@Service
public class DecisionReadService {

    private final RecordViewAssembler assembler;
    private final DecisionReadDao dao;
    private final Clock clock;

    public DecisionReadService(RecordViewAssembler assembler, DecisionReadDao dao, Clock clock) {
        this.assembler = assembler;
        this.dao = dao;
        this.clock = clock;
    }

    public record SingleResult(DecisionResponseDto body, long snapshotVersion) {}

    public record WholeResult(WholeAccountResponseDto body, long snapshotVersion) {}

    public record CapabilityOneResult(CapabilityDescriptorDto body, long snapshotVersion) {}

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public SingleResult single(String accountExternalId, String capabilityKey, Long minSnapshotVersion) {
        var key = parseKey(capabilityKey);
        var view = assembler.pointView(accountExternalId, capabilityKey);
        if (minSnapshotVersion != null && view.snapshotVersion() < minSnapshotVersion) {
            throw new EntitlementApiException(ErrorCode.SNAPSHOT_BEHIND,
                "Current snapshot version " + view.snapshotVersion() + " is behind the requested " + minSnapshotVersion + ".",
                Map.of("currentVersion", view.snapshotVersion()));
        }
        var explanation = Resolver.explain(view, accountExternalId, key, clock.instant());
        var capability = view.capability(key).orElseThrow();
        return new SingleResult(DecisionMapper.toResponse(explanation, capability), view.snapshotVersion());
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public WholeResult whole(String accountExternalId) {
        var view = assembler.accountView(accountExternalId);
        var account = view.account(accountExternalId).orElseThrow(() -> new UnknownAccountException(accountExternalId));
        // One evaluatedAt for the whole response: every entitlement in it is answered as of the same
        // instant, against the same view (c31).
        Instant evaluatedAt = clock.instant();
        var entitlements = view.activeCapabilities().stream()
            .sorted(Comparator.comparing(c -> c.key().value()))
            .map(capability -> {
                var decision = Resolver.resolve(view, accountExternalId, capability.key(), evaluatedAt);
                return new WholeAccountResponseDto.Entitlement(capability.key().value(), decision.allowed(), ValueMapper.toDto(decision.value()));
            }).toList();
        var body = new WholeAccountResponseDto(accountExternalId, account.planKey(), view.snapshotVersion(),
            Timestamps.iso(evaluatedAt), entitlements);
        return new WholeResult(body, view.snapshotVersion());
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public CapabilityListResponseDto capabilityList(String area, String status) {
        if (!status.equals("ACTIVE") && !status.equals("RETIRED") && !status.equals("ALL")) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "status must be one of ACTIVE, RETIRED, ALL",
                Map.of("violations", List.of("status: must be one of ACTIVE, RETIRED, ALL")));
        }
        String effectiveStatus = "ALL".equals(status) ? null : status;
        var rows = dao.allCapabilities(area, effectiveStatus, null);
        var tiersByCapabilityId = dao.allTiers();
        var descriptors = rows.stream()
            .map(row -> RowMappers.toCapability(row, tiersByCapabilityId.getOrDefault(row.id(), List.of())))
            .sorted(Comparator.comparing(c -> c.key().value()))
            .map(CapabilityDescriptorMapper::toDescriptor)
            .toList();
        return new CapabilityListResponseDto(descriptors, dao.latestVersion());
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public CapabilityOneResult capabilityOne(String capabilityKey) {
        var row = dao.capabilityByKey(capabilityKey)
            .orElseThrow(() -> new UnknownCapabilityException(capabilityKey));
        var capability = RowMappers.toCapability(row, dao.tiers(row.id()));
        return new CapabilityOneResult(CapabilityDescriptorMapper.toDescriptor(capability), dao.latestVersion());
    }

    /** A malformed key is an unknown capability, not a 500 — the registry simply has no such key. */
    private static CapabilityKey parseKey(String raw) {
        try {
            return new CapabilityKey(raw);
        }
        catch (IllegalArgumentException e) {
            throw new UnknownCapabilityException(raw);
        }
    }
}
