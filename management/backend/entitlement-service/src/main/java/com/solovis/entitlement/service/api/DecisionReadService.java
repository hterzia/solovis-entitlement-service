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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
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

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public SingleResult single(String accountExternalId, String capabilityKey, Long minSnapshotVersion) {
        var view = assembler.pointView(accountExternalId, capabilityKey);
        if (minSnapshotVersion != null && view.snapshotVersion() < minSnapshotVersion) {
            throw new EntitlementApiException(ErrorCode.SNAPSHOT_BEHIND,
                "Current snapshot version " + view.snapshotVersion() + " is behind the requested " + minSnapshotVersion + ".",
                Map.of("currentVersion", view.snapshotVersion()));
        }
        var key = new CapabilityKey(capabilityKey);
        var explanation = Resolver.explain(view, accountExternalId, key, clock.instant());
        var capability = view.capability(key).orElseThrow();
        return new SingleResult(DecisionMapper.toResponse(explanation, capability), view.snapshotVersion());
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public WholeResult whole(String accountExternalId) {
        var view = assembler.accountView(accountExternalId);
        var account = view.account(accountExternalId).orElseThrow(() -> new UnknownAccountException(accountExternalId));
        var entitlements = view.activeCapabilities().stream()
            .sorted(Comparator.comparing(c -> c.key().value()))
            .map(capability -> {
                var decision = Resolver.resolve(view, accountExternalId, capability.key(), clock.instant());
                return new WholeAccountResponseDto.Entitlement(capability.key().value(), decision.allowed(), ValueMapper.toDto(decision.value()));
            }).toList();
        var body = new WholeAccountResponseDto(accountExternalId, account.planKey(), view.snapshotVersion(),
            clock.instant().toString(), entitlements);
        return new WholeResult(body, view.snapshotVersion());
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public CapabilityListResponseDto capabilityList(String area, String status) {
        String effectiveStatus = "ALL".equals(status) ? null : "RETIRED".equals(status) ? "RETIRED" : "ACTIVE";
        var rows = dao.allCapabilities(area, effectiveStatus, null);
        var tiersByCapabilityId = dao.allTiers();
        var descriptors = rows.stream()
            .map(row -> RowMappers.toCapability(row, tiersByCapabilityId.getOrDefault(row.id(), java.util.List.of())))
            .sorted(Comparator.comparing(c -> c.key().value()))
            .map(CapabilityDescriptorMapper::toDescriptor)
            .toList();
        return new CapabilityListResponseDto(descriptors, dao.latestVersion());
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public CapabilityDescriptorDto capabilityOne(String capabilityKey) {
        var row = dao.capabilityByKey(capabilityKey)
            .orElseThrow(() -> new UnknownCapabilityException(capabilityKey));
        var capability = RowMappers.toCapability(row, dao.tiers(row.id()));
        return CapabilityDescriptorMapper.toDescriptor(capability);
    }
}
