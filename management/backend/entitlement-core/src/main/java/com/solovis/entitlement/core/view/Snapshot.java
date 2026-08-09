package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The published, immutable state of the model at one moment (data-model.md "Snapshot version").
 * Every field is an unmodifiable map so a reader holding this instance can never observe a
 * half-applied change — {@link SnapshotMutator} always produces a new instance rather than
 * mutating this one.
 */
public final class Snapshot implements EntitlementView {

    private final long version;
    private final Map<CapabilityKey, Capability> capabilities;
    private final Map<String, Plan> plans;
    private final Map<PlanCapabilityKey, PlanEntitlement> planEntitlements;
    private final Map<String, AccountAssignment> accounts;
    private final Map<AccountCapabilityKey, List<AccountOverride>> liveOverrides;

    Snapshot(
        long version,
        Map<CapabilityKey, Capability> capabilities,
        Map<String, Plan> plans,
        Map<PlanCapabilityKey, PlanEntitlement> planEntitlements,
        Map<String, AccountAssignment> accounts,
        Map<AccountCapabilityKey, List<AccountOverride>> liveOverrides) {
        this.version = version;
        this.capabilities = capabilities;
        this.plans = plans;
        this.planEntitlements = planEntitlements;
        this.accounts = accounts;
        this.liveOverrides = liveOverrides;
    }

    @Override
    public long snapshotVersion() {
        return version;
    }

    @Override
    public Optional<Capability> capability(CapabilityKey key) {
        return Optional.ofNullable(capabilities.get(key));
    }

    @Override
    public Collection<Capability> activeCapabilities() {
        return capabilities.values().stream().filter(c -> !c.isRetired()).toList();
    }

    @Override
    public Optional<AccountAssignment> account(String accountExternalId) {
        return Optional.ofNullable(accounts.get(accountExternalId));
    }

    @Override
    public Optional<PlanEntitlement> planEntitlement(String planKey, CapabilityKey capabilityKey) {
        return Optional.ofNullable(planEntitlements.get(new PlanCapabilityKey(planKey, capabilityKey)));
    }

    @Override
    public List<AccountOverride> liveOverrides(String accountExternalId, CapabilityKey capabilityKey) {
        return liveOverrides.getOrDefault(new AccountCapabilityKey(accountExternalId, capabilityKey), List.of());
    }

    Optional<Plan> plan(String planKey) {
        return Optional.ofNullable(plans.get(planKey));
    }

    // Package-visible accessors SnapshotMutator uses to rebuild only the maps a change touches.
    Map<CapabilityKey, Capability> capabilitiesMap() { return capabilities; }
    Map<String, Plan> plansMap() { return plans; }
    Map<PlanCapabilityKey, PlanEntitlement> planEntitlementsMap() { return planEntitlements; }
    Map<String, AccountAssignment> accountsMap() { return accounts; }
    Map<AccountCapabilityKey, List<AccountOverride>> liveOverridesMap() { return liveOverrides; }

    record PlanCapabilityKey(String planKey, CapabilityKey capabilityKey) {}

    record AccountCapabilityKey(String accountExternalId, CapabilityKey capabilityKey) {}
}
