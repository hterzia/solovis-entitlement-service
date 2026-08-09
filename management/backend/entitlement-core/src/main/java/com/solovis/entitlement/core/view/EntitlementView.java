package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.PlanEntitlement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The read contract {@link com.solovis.entitlement.core.engine.Resolver} needs. {@link Snapshot}
 * is the only production implementation; the interface exists so tests can supply a minimal
 * fixture without constructing a full snapshot.
 */
public interface EntitlementView {

    long snapshotVersion();

    Optional<Capability> capability(CapabilityKey key);

    /** Every non-retired capability, for whole-account resolution (c20). */
    Collection<Capability> activeCapabilities();

    Optional<AccountAssignment> account(String accountExternalId);

    Optional<PlanEntitlement> planEntitlement(String planKey, CapabilityKey capabilityKey);

    /** Every LIVE override of either kind for this account and capability (§4). */
    List<AccountOverride> liveOverrides(String accountExternalId, CapabilityKey capabilityKey);
}
