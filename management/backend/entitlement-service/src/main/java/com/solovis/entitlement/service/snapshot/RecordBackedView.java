package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.StandingOverride;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An {@link com.solovis.entitlement.core.view.EntitlementView} over rows read from SQLite for one
 * account, rather than over the whole model held in memory. {@link RecordViewAssembler} loads every
 * field inside its transaction and hands the finished maps here, so the view itself never touches
 * JDBC and is trivially testable.
 *
 * <p>Two shapes. A {@link Mode#POINT} view carries one account and one capability — the four indexed
 * lookups a single check needs. A {@link Mode#ACCOUNT_SLICE} view carries the account and the whole
 * capability registry, so whole-account resolution can loop {@code Resolver} over
 * {@link #activeCapabilities()}. Both key their maps the way {@code Snapshot} keys its own; the
 * capability map deliberately holds retired capabilities too, because {@code Resolver.lookUp}
 * distinguishes "no such capability" from "retired" by finding it and then asking.
 *
 * <p>The methods only the replication feed needs throw — the feed assembles a real
 * {@link com.solovis.entitlement.core.view.Snapshot}, and a loud throw beats a silently empty answer.
 */
public final class RecordBackedView implements com.solovis.entitlement.core.view.EntitlementView {

    enum Mode { POINT, ACCOUNT_SLICE }

    private final Mode mode;
    private final long snapshotVersion;
    private final String accountExternalId;
    private final AccountAssignment account;
    private final Map<CapabilityKey, Capability> capabilities;
    private final Map<CapabilityKey, PlanEntitlement> planEntitlements;
    private final Map<CapabilityKey, List<AccountOverride>> liveOverrides;
    private final Map<CapabilityKey, List<StandingOverride>> knownOverrides;

    RecordBackedView(
        Mode mode,
        long snapshotVersion,
        String accountExternalId,
        AccountAssignment account,
        Map<CapabilityKey, Capability> capabilities,
        Map<CapabilityKey, PlanEntitlement> planEntitlements,
        Map<CapabilityKey, List<AccountOverride>> liveOverrides,
        Map<CapabilityKey, List<StandingOverride>> knownOverrides) {
        this.mode = mode;
        this.snapshotVersion = snapshotVersion;
        this.accountExternalId = accountExternalId;
        this.account = account;
        this.capabilities = capabilities;
        this.planEntitlements = planEntitlements;
        this.liveOverrides = liveOverrides;
        this.knownOverrides = knownOverrides;
    }

    @Override
    public long snapshotVersion() {
        return snapshotVersion;
    }

    @Override
    public Optional<Capability> capability(CapabilityKey key) {
        return Optional.ofNullable(capabilities.get(key));
    }

    @Override
    public Optional<AccountAssignment> account(String externalId) {
        requireInScope(externalId);
        return Optional.ofNullable(account);
    }

    @Override
    public Optional<PlanEntitlement> planEntitlement(String planKey, CapabilityKey capabilityKey) {
        if (account == null || !account.planKey().equals(planKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(planEntitlements.get(capabilityKey));
    }

    @Override
    public List<AccountOverride> liveOverrides(String externalId, CapabilityKey capabilityKey) {
        requireInScope(externalId);
        return liveOverrides.getOrDefault(capabilityKey, List.of());
    }

    /**
     * Every override on this capability that exists, each paired with what it is doing now — the
     * wider set {@code Resolver.explain} needs to say "there was a GRANT of 200 and it ended on 30
     * June" (c19–c21). The default on {@code EntitlementView} reports only the in-force ones, which
     * is right for a replica's projection and wrong here: the record is the one place that still
     * knows about the overrides a window has taken out of play.
     *
     * <p>The obligation the interface states is checked structurally rather than trusted: filtering
     * this to {@code IN_FORCE} must reproduce {@link #liveOverrides} exactly, since both come from
     * the same rows evaluated against the same date inside one read transaction.
     */
    @Override
    public List<StandingOverride> knownOverrides(String externalId, CapabilityKey capabilityKey) {
        requireInScope(externalId);
        return knownOverrides.getOrDefault(capabilityKey, List.of());
    }

    @Override
    public Collection<Capability> activeCapabilities() {
        if (mode != Mode.ACCOUNT_SLICE) {
            throw unsupported("activeCapabilities");
        }
        return capabilities.values().stream().filter(c -> !c.isRetired()).toList();
    }

    @Override
    public Collection<Capability> capabilities() {
        throw unsupported("capabilities");
    }

    @Override
    public Collection<AccountAssignment> accountAssignments() {
        throw unsupported("accountAssignments");
    }

    @Override
    public Collection<AccountOverride> allLiveOverrides() {
        throw unsupported("allLiveOverrides");
    }

    @Override
    public Optional<Plan> plan(String planKey) {
        throw unsupported("plan");
    }

    @Override
    public Collection<Plan> plans() {
        throw unsupported("plans");
    }

    /**
     * A copy of this view with one override gone — what the remove-override confirmation resolves
     * against to state the value an operator is about to return to. Preview and removal therefore
     * still answer through the same {@code Resolver.explain}, so they cannot disagree.
     */
    public RecordBackedView withoutOverride(long overrideId) {
        var remaining = new LinkedHashMap<CapabilityKey, List<AccountOverride>>();
        for (var entry : liveOverrides.entrySet()) {
            var kept = entry.getValue().stream()
                .filter(o -> !o.id().equals(OptionalLong.of(overrideId)))
                .toList();
            if (!kept.isEmpty()) {
                remaining.put(entry.getKey(), kept);
            }
        }
        // Dropped from the known set too, not only the live one. The interface's obligation is that
        // filtering knownOverrides to IN_FORCE reproduces liveOverrides; leaving it in one and not
        // the other would make the preview's trace describe a different computation from its value.
        var remainingKnown = new LinkedHashMap<CapabilityKey, List<StandingOverride>>();
        for (var entry : knownOverrides.entrySet()) {
            var kept = entry.getValue().stream()
                .filter(s -> !s.override().id().equals(OptionalLong.of(overrideId)))
                .toList();
            if (!kept.isEmpty()) {
                remainingKnown.put(entry.getKey(), kept);
            }
        }
        return new RecordBackedView(mode, snapshotVersion, accountExternalId, account,
            capabilities, planEntitlements, Map.copyOf(remaining), Map.copyOf(remainingKnown));
    }

    private void requireInScope(String externalId) {
        if (!accountExternalId.equals(externalId)) {
            throw new IllegalArgumentException("This view was built for account '" + accountExternalId
                + "' and cannot answer for '" + externalId + "'.");
        }
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
            method + " is not supported by RecordBackedView — feed assembly uses SnapshotAssembler instead.");
    }
}
