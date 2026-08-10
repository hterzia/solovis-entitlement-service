package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import com.solovis.entitlement.service.store.DecisionReadDao;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds a complete {@link Snapshot} by walking the read DAO — startup, and the snapshot feed's full-resync path. */
@Component
public class SnapshotAssembler {

    private final DecisionReadDao decisionReadDao;
    private final Clock clock;

    public SnapshotAssembler(DecisionReadDao decisionReadDao, Clock clock) {
        this.decisionReadDao = decisionReadDao;
        this.clock = clock;
    }

    public Snapshot assembleFull() {
        long version = decisionReadDao.latestVersion();
        LocalDate today = LocalDate.now(clock);
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

        // In force today, not merely un-removed. A snapshot assembled from scratch — which every
        // full resync is — must not resurrect an override that has ended, nor start one that has not
        // begun. Windows are evaluated here, before publication, and never inside a replica: c14
        // requires a cut-off product to go on honouring an ended override, which a replica able to
        // evaluate its own windows would not do.
        for (var row : decisionReadDao.allInForceOverrides(today)) {
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
