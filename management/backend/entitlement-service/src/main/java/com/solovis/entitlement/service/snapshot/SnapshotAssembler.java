package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/** Builds a complete {@link Snapshot} by walking every repository — startup, and the snapshot feed's full-resync path. */
@Component
public class SnapshotAssembler {

    private final CapabilityRepository capabilityRepository;
    private final PlanRepository planRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final AccountRepository accountRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final SnapshotVersionRepository snapshotVersionRepository;

    public SnapshotAssembler(
        CapabilityRepository capabilityRepository, PlanRepository planRepository,
        PlanEntitlementRepository planEntitlementRepository, AccountRepository accountRepository,
        AccountOverrideRepository accountOverrideRepository, SnapshotVersionRepository snapshotVersionRepository) {
        this.capabilityRepository = capabilityRepository;
        this.planRepository = planRepository;
        this.planEntitlementRepository = planEntitlementRepository;
        this.accountRepository = accountRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.snapshotVersionRepository = snapshotVersionRepository;
    }

    public Snapshot assembleFull() {
        long version = snapshotVersionRepository.findLatest().map(SnapshotVersionRow::version).orElse(0L);
        var builder = new SnapshotBuilder();

        Map<Long, Capability> capabilitiesById = new HashMap<>();
        for (var row : capabilityRepository.findAll(null, null, null)) {
            var capability = RowMappers.toCapability(row, capabilityRepository.findTiers(row.id()));
            capabilitiesById.put(row.id(), capability);
            builder.capability(capability);
        }

        Map<Long, String> planKeysById = new HashMap<>();
        for (var row : planRepository.findAll(null)) {
            builder.plan(RowMappers.toPlan(row));
            planKeysById.put(row.id(), row.key());
        }
        for (var planId : planKeysById.keySet()) {
            for (var row : planEntitlementRepository.findByPlan(planId)) {
                var capability = capabilitiesById.get(row.capabilityId());
                builder.planEntitlement(RowMappers.toPlanEntitlement(row, planKeysById.get(planId), capability));
            }
        }

        Map<Long, String> externalIdsById = new HashMap<>();
        for (var row : accountRepository.findAllActive()) {
            String planKey = planKeysById.get(row.planId());
            builder.account(new AccountAssignment(row.externalId(), planKey));
            externalIdsById.put(row.id(), row.externalId());
        }

        for (var row : accountOverrideRepository.findAllLive()) {
            String externalId = externalIdsById.get(row.accountId());
            if (externalId == null) {
                continue; // account is CLOSED or otherwise excluded from the active set
            }
            var capability = capabilitiesById.get(row.capabilityId());
            builder.override(RowMappers.toOverride(row, externalId, capability));
        }

        return builder.build(version);
    }
}
