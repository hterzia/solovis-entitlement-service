package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import com.solovis.entitlement.service.store.DecisionReadDao;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds a complete {@link Snapshot} by walking the read DAO — startup, and the snapshot feed's full-resync path. */
@Component
public class SnapshotAssembler {

    private final DecisionReadDao decisionReadDao;

    public SnapshotAssembler(DecisionReadDao decisionReadDao) {
        this.decisionReadDao = decisionReadDao;
    }

    public Snapshot assembleFull() {
        long version = decisionReadDao.latestVersion();
        var builder = new SnapshotBuilder();

        var tiersByCapabilityId = decisionReadDao.allTiers();
        Map<Long, Capability> capabilitiesById = new HashMap<>();
        for (var row : decisionReadDao.allCapabilities(null, null, null)) {
            var capability = RowMappers.toCapability(row, tiersByCapabilityId.getOrDefault(row.id(), List.of()));
            capabilitiesById.put(row.id(), capability);
            builder.capability(capability);
        }

        Map<Long, String> planKeysById = new HashMap<>();
        for (var row : decisionReadDao.allPlans()) {
            builder.plan(RowMappers.toPlan(row));
            planKeysById.put(row.id(), row.key());
        }
        for (var planId : planKeysById.keySet()) {
            for (var row : decisionReadDao.entitlementsForPlan(planId)) {
                var capability = capabilitiesById.get(row.capabilityId());
                builder.planEntitlement(RowMappers.toPlanEntitlement(row, planKeysById.get(planId), capability));
            }
        }

        Map<Long, String> externalIdsById = new HashMap<>();
        for (var row : decisionReadDao.activeAccounts()) {
            String planKey = planKeysById.get(row.planId());
            builder.account(new AccountAssignment(row.externalId(), planKey));
            externalIdsById.put(row.id(), row.externalId());
        }

        for (var row : decisionReadDao.allLiveOverrides()) {
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
