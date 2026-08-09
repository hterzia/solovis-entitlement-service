package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotMutatorTest {

    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");

    private static Capability reportsCapability() {
        return new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    }

    private Snapshot baseSnapshot() {
        return new SnapshotBuilder()
            .capability(reportsCapability())
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_1", "pro"))
            .build(1);
    }

    @Test
    void withPlanEntitlementReplacesOnlyTheChangedValueAndBumpsTheVersion() {
        var base = baseSnapshot();
        var updated = SnapshotMutator.withPlanEntitlement(
            base, 2, new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(999)));

        assertThat(updated.snapshotVersion()).isEqualTo(2);
        assertThat(updated.planEntitlement("pro", REPORTS)).contains(
            new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(999)));
        // The base snapshot is untouched — readers holding it never see the change (c31).
        assertThat(base.planEntitlement("pro", REPORTS)).contains(
            new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)));
    }

    @Test
    void withPlanEntitlementReusesUnrelatedMapsByReference() {
        var base = baseSnapshot();
        var updated = SnapshotMutator.withPlanEntitlement(
            base, 2, new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(999)));

        assertThat(updated.capabilitiesMap()).isSameAs(base.capabilitiesMap());
        assertThat(updated.accountsMap()).isSameAs(base.accountsMap());
        assertThat(updated.liveOverridesMap()).isSameAs(base.liveOverridesMap());
        assertThat(updated.planEntitlementsMap()).isNotSameAs(base.planEntitlementsMap());
    }

    @Test
    void withOverrideAddedAppendsToTheAccountCapabilityBucketOnly() {
        var base = baseSnapshot();
        var override = new AccountOverride(OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("goodwill"), Optional.of("actor"), Optional.empty());

        var updated = SnapshotMutator.withOverrideAdded(base, 2, override);

        assertThat(updated.liveOverrides("acct_1", REPORTS)).containsExactly(override);
        assertThat(base.liveOverrides("acct_1", REPORTS)).isEmpty();
        assertThat(updated.planEntitlementsMap()).isSameAs(base.planEntitlementsMap());
        assertThat(updated.capabilitiesMap()).isSameAs(base.capabilitiesMap());
        assertThat(updated.accountsMap()).isSameAs(base.accountsMap());
        assertThat(updated.liveOverridesMap()).isNotSameAs(base.liveOverridesMap());
    }

    @Test
    void withOverrideRemovedDropsOnlyTheNamedOverride() {
        var withOverride = SnapshotMutator.withOverrideAdded(baseSnapshot(), 2, new AccountOverride(
            OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.GRANT, EntitlementValue.Quantity.of(200),
            Optional.of("goodwill"), Optional.of("actor"), Optional.empty()));

        var updated = SnapshotMutator.withOverrideRemoved(withOverride, 3, "acct_1", REPORTS, 1);

        assertThat(updated.liveOverrides("acct_1", REPORTS)).isEmpty();
        assertThat(withOverride.liveOverrides("acct_1", REPORTS)).hasSize(1); // prior version untouched
        assertThat(updated.capabilitiesMap()).isSameAs(withOverride.capabilitiesMap());
        assertThat(updated.accountsMap()).isSameAs(withOverride.accountsMap());
        assertThat(updated.planEntitlementsMap()).isSameAs(withOverride.planEntitlementsMap());
        assertThat(updated.liveOverridesMap()).isNotSameAs(withOverride.liveOverridesMap());
        assertThat(updated.liveOverridesMap()).doesNotContainKey(
            new Snapshot.AccountCapabilityKey("acct_1", REPORTS));
    }

    @Test
    void withPlanReplacesTheStatusAndReusesUnrelatedMapsByReference() {
        var base = baseSnapshot();
        var archived = new Plan("pro", "Pro", Plan.Status.ARCHIVED, false);

        var updated = SnapshotMutator.withPlan(base, 2, archived);

        assertThat(updated.plan("pro")).contains(archived);
        assertThat(base.plan("pro")).get().extracting(Plan::status).isEqualTo(Plan.Status.ACTIVE);
        assertThat(updated.capabilitiesMap()).isSameAs(base.capabilitiesMap());
        assertThat(updated.planEntitlementsMap()).isSameAs(base.planEntitlementsMap());
        assertThat(updated.accountsMap()).isSameAs(base.accountsMap());
        assertThat(updated.liveOverridesMap()).isSameAs(base.liveOverridesMap());
        assertThat(updated.plansMap()).isNotSameAs(base.plansMap());
    }

    @Test
    void withAccountReplacesThePlanAssignment() {
        var base = baseSnapshot();
        var updated = SnapshotMutator.withAccount(base, 2, new AccountAssignment("acct_1", "enterprise"));

        assertThat(updated.account("acct_1")).contains(new AccountAssignment("acct_1", "enterprise"));
        assertThat(base.account("acct_1")).contains(new AccountAssignment("acct_1", "pro"));
        assertThat(updated.capabilitiesMap()).isSameAs(base.capabilitiesMap());
        assertThat(updated.planEntitlementsMap()).isSameAs(base.planEntitlementsMap());
        assertThat(updated.liveOverridesMap()).isSameAs(base.liveOverridesMap());
        assertThat(updated.accountsMap()).isNotSameAs(base.accountsMap());
    }

    @Test
    void withPlanEntitlementRemovedFallsBackToCapabilityDefault() {
        var key = new CapabilityKey("export.parquet");
        var capability = new Capability(key, "Export", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        var base = new SnapshotBuilder().capability(capability)
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", key, new EntitlementValue.Switch(true)))
            .account(new AccountAssignment("acct_1", "pro"))
            .build(1);

        var next = SnapshotMutator.withPlanEntitlementRemoved(base, 2, "pro", key);

        assertThat(next.planEntitlement("pro", key)).isEmpty();
        var decision = com.solovis.entitlement.core.engine.Resolver.resolve(next, "acct_1", key, java.time.Instant.now());
        assertThat(decision.value()).isEqualTo(new EntitlementValue.Switch(false)); // capability default, not the removed plan value
    }

    @Test
    void withCapabilityReplacesTheRegistryEntry() {
        var base = baseSnapshot();
        var retired = new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.RETIRED,
            java.time.Instant.now());

        var updated = SnapshotMutator.withCapability(base, 2, retired);

        assertThat(updated.capability(REPORTS)).get().extracting(Capability::isRetired).isEqualTo(true);
        assertThat(base.capability(REPORTS)).get().extracting(Capability::isRetired).isEqualTo(false);
        assertThat(updated.accountsMap()).isSameAs(base.accountsMap());
        assertThat(updated.planEntitlementsMap()).isSameAs(base.planEntitlementsMap());
        assertThat(updated.liveOverridesMap()).isSameAs(base.liveOverridesMap());
        assertThat(updated.capabilitiesMap()).isNotSameAs(base.capabilitiesMap());
    }
}
