package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Assembles a {@link Snapshot} from scratch — a full DB load or a full replica resync. */
public final class SnapshotBuilder {

    private final Map<CapabilityKey, Capability> capabilities = new HashMap<>();
    private final Map<String, Plan> plans = new HashMap<>();
    private final Map<Snapshot.PlanCapabilityKey, PlanEntitlement> planEntitlements = new HashMap<>();
    private final Map<String, AccountAssignment> accounts = new HashMap<>();
    private final Map<Snapshot.AccountCapabilityKey, List<AccountOverride>> liveOverrides = new HashMap<>();

    public SnapshotBuilder capability(Capability capability) {
        capabilities.put(capability.key(), capability);
        return this;
    }

    public SnapshotBuilder plan(Plan plan) {
        plans.put(plan.key(), plan);
        return this;
    }

    public SnapshotBuilder planEntitlement(PlanEntitlement entitlement) {
        planEntitlements.put(
            new Snapshot.PlanCapabilityKey(entitlement.planKey(), entitlement.capabilityKey()), entitlement);
        return this;
    }

    public SnapshotBuilder account(AccountAssignment account) {
        accounts.put(account.accountExternalId(), account);
        return this;
    }

    public SnapshotBuilder override(AccountOverride override) {
        var key = new Snapshot.AccountCapabilityKey(override.accountExternalId(), override.capabilityKey());
        liveOverrides.computeIfAbsent(key, k -> new ArrayList<>()).add(override);
        return this;
    }

    public Snapshot build(long version) {
        var frozenOverrides = new HashMap<Snapshot.AccountCapabilityKey, List<AccountOverride>>();
        liveOverrides.forEach((key, value) -> frozenOverrides.put(key, List.copyOf(value)));
        return new Snapshot(
            version,
            Map.copyOf(capabilities),
            Map.copyOf(plans),
            Map.copyOf(planEntitlements),
            Map.copyOf(accounts),
            Map.copyOf(frozenOverrides));
    }
}
