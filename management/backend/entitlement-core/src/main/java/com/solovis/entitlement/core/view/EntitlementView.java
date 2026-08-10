package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.StandingOverride;
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

    /** Every declared capability, retired or not — the registry read needs this; resolution never does. */
    Collection<Capability> capabilities();

    /** Every non-retired capability, for whole-account resolution (c20). */
    Collection<Capability> activeCapabilities();

    Optional<AccountAssignment> account(String accountExternalId);

    /** Every account's plan assignment, for the snapshot replication feed's full resync. */
    Collection<AccountAssignment> accountAssignments();

    Optional<PlanEntitlement> planEntitlement(String planKey, CapabilityKey capabilityKey);

    /** Every LIVE override of either kind for this account and capability (§4). */
    List<AccountOverride> liveOverrides(String accountExternalId, CapabilityKey capabilityKey);

    /**
     * Every override of either kind that <em>existed</em> at the moment this view describes,
     * whatever it was doing then — in force, not yet begun, ended, or removed (002 c19).
     *
     * <p>This is what lets an explanation say *"there was a GRANT of 200 and it ended on 30 June"*
     * rather than the true but useless *"no GRANT in force"*. Only {@code explain} reads it;
     * {@code resolve} stays on {@link #liveOverrides} and is untouched by 002.
     *
     * <p><strong>Implementations must keep the two consistent:</strong> filtering this to
     * {@link com.solovis.entitlement.core.model.OverrideStanding#IN_FORCE} must yield exactly
     * {@code liveOverrides}. If they disagree, a trace would describe a different computation from
     * the one that produced the value, which is the single thing v1 criterion 24 forbids.
     *
     * <p>The default satisfies that trivially by reporting every live override as in force, which
     * is correct for any view that holds nothing else — a replica's projection, and every test
     * fixture written before 002.
     */
    default List<StandingOverride> knownOverrides(String accountExternalId, CapabilityKey capabilityKey) {
        return liveOverrides(accountExternalId, capabilityKey).stream()
            .map(StandingOverride::inForce)
            .toList();
    }

    /** Every LIVE override across all accounts and capabilities, for the snapshot replication feed's full resync. */
    Collection<AccountOverride> allLiveOverrides();

    /** A single plan by key, for the operator UI's plan list and editor. */
    Optional<Plan> plan(String planKey);

    /** Every plan, for the operator UI's plan list. */
    Collection<Plan> plans();
}
