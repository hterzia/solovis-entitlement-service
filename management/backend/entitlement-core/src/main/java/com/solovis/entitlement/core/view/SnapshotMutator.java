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

/**
 * Produces version N+1 of a {@link Snapshot} from version N and one change, rebuilding only the
 * map the change touches and reusing every other map by reference (research.md §8) — readers
 * holding the old {@link Snapshot} instance never observe the new one, and the swap the caller
 * performs on an {@code AtomicReference} is therefore always atomic from a reader's perspective.
 */
public final class SnapshotMutator {

    private SnapshotMutator() {}

    public static Snapshot withCapability(Snapshot base, long newVersion, Capability capability) {
        var capabilities = new HashMap<>(base.capabilitiesMap());
        capabilities.put(capability.key(), capability);
        return new Snapshot(newVersion, Map.copyOf(capabilities), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), base.liveOverridesMap());
    }

    public static Snapshot withPlanEntitlement(Snapshot base, long newVersion, PlanEntitlement entitlement) {
        var planEntitlements = new HashMap<>(base.planEntitlementsMap());
        planEntitlements.put(
            new Snapshot.PlanCapabilityKey(entitlement.planKey(), entitlement.capabilityKey()), entitlement);
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            Map.copyOf(planEntitlements), base.accountsMap(), base.liveOverridesMap());
    }

    public static Snapshot withPlanEntitlementRemoved(Snapshot base, long newVersion, String planKey, CapabilityKey capabilityKey) {
        var planEntitlements = new HashMap<>(base.planEntitlementsMap());
        planEntitlements.remove(new Snapshot.PlanCapabilityKey(planKey, capabilityKey));
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            Map.copyOf(planEntitlements), base.accountsMap(), base.liveOverridesMap());
    }

    public static Snapshot withAccount(Snapshot base, long newVersion, AccountAssignment account) {
        var accounts = new HashMap<>(base.accountsMap());
        accounts.put(account.accountExternalId(), account);
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), Map.copyOf(accounts), base.liveOverridesMap());
    }

    public static Snapshot withOverrideAdded(Snapshot base, long newVersion, AccountOverride override) {
        var key = new Snapshot.AccountCapabilityKey(override.accountExternalId(), override.capabilityKey());
        var overrides = new HashMap<>(base.liveOverridesMap());
        var bucket = new ArrayList<>(overrides.getOrDefault(key, List.of()));
        bucket.add(override);
        overrides.put(key, List.copyOf(bucket));
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), Map.copyOf(overrides));
    }

    public static Snapshot withOverrideRemoved(
        Snapshot base, long newVersion, String accountExternalId, CapabilityKey capabilityKey, long overrideId) {
        var key = new Snapshot.AccountCapabilityKey(accountExternalId, capabilityKey);
        var overrides = new HashMap<>(base.liveOverridesMap());
        var remaining = overrides.getOrDefault(key, List.of()).stream()
            .filter(o -> !o.id().equals(java.util.OptionalLong.of(overrideId)))
            .toList();
        if (remaining.isEmpty()) {
            overrides.remove(key);
        } else {
            overrides.put(key, remaining);
        }
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), Map.copyOf(overrides));
    }

    /**
     * The same model at a new version. A delta change that turns out to be a no-op on this replica —
     * a removal it never saw, a redelivered creation — still advances the version, because the
     * replica has genuinely caught up to it. Without this the version would stall and the replica
     * would re-request the same change forever.
     */
    public static Snapshot withVersion(Snapshot base, long newVersion) {
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), base.liveOverridesMap());
    }

    public static Snapshot withPlan(Snapshot base, long newVersion, Plan plan) {
        var plans = new HashMap<>(base.plansMap());
        plans.put(plan.key(), plan);
        return new Snapshot(newVersion, base.capabilitiesMap(), Map.copyOf(plans),
            base.planEntitlementsMap(), base.accountsMap(), base.liveOverridesMap());
    }
}
