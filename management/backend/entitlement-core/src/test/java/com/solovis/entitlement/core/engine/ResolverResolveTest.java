package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolverResolveTest {

    private static final CapabilityKey API_ACCESS = new CapabilityKey("api.access");
    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");
    private static final CapabilityKey SEATS = new CapabilityKey("seats.count");
    private static final CapabilityKey SUPPORT = new CapabilityKey("support.tier");
    private static final CapabilityKey SLA = new CapabilityKey("sla.tier");
    private static final Instant NOW = Instant.parse("2026-08-09T14:03:11.482Z");

    private static Capability switchCapability(CapabilityKey key) {
        return new Capability(key, key.value(), null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    }

    private static Capability quantityCapability(CapabilityKey key, EntitlementValue defaultValue) {
        return new Capability(key, key.value(), null, ValueType.QUANTITY,
            defaultValue, Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    }

    private static AccountOverride grant(CapabilityKey key, long id, EntitlementValue value) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", key, OverrideKind.GRANT, value,
            Optional.of("reason"), Optional.of("actor"), Optional.of(NOW));
    }

    private static AccountOverride hold(CapabilityKey key, long id, EntitlementValue value) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", key, OverrideKind.HOLD, value,
            Optional.of("reason"), Optional.of("actor"), Optional.of(NOW));
    }

    // §5 worked examples, transcribed literally.

    @Test
    void switchFalsePlanNoOverridesIsDisallowed() {
        var snapshot = new SnapshotBuilder()
            .capability(switchCapability(API_ACCESS))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("free", API_ACCESS, new EntitlementValue.Switch(false)))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", API_ACCESS, NOW);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.value()).isEqualTo(new EntitlementValue.Switch(false));
    }

    @Test
    void switchFalsePlanWithGrantTrueIsAllowed() {
        var snapshot = new SnapshotBuilder()
            .capability(switchCapability(API_ACCESS))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("free", API_ACCESS, new EntitlementValue.Switch(false)))
            .account(new AccountAssignment("acct_1", "free"))
            .override(grant(API_ACCESS, 1, new EntitlementValue.Switch(true)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", API_ACCESS, NOW).allowed()).isTrue();
    }

    @Test
    void switchTruePlanWithHoldFalseIsDisallowed() {
        var snapshot = new SnapshotBuilder()
            .capability(switchCapability(API_ACCESS))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("pro", API_ACCESS, new EntitlementValue.Switch(true)))
            .account(new AccountAssignment("acct_1", "pro"))
            .override(hold(API_ACCESS, 1, new EntitlementValue.Switch(false)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", API_ACCESS, NOW).allowed()).isFalse();
    }

    @Test
    void unmentionedQuantityResolvesToTheCapabilityDefault() {
        var snapshot = new SnapshotBuilder()
            .capability(quantityCapability(REPORTS, EntitlementValue.Quantity.of(0)))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", REPORTS, NOW);
        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(decision.allowed()).isTrue(); // no off-value declared (c10)
    }

    @Test
    void grantMoreGenerousThanPlanWins() {
        var snapshot = reportsSnapshotWithPlanValue(50)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(200));
    }

    @Test
    void holdAfterGrantSuppressesTheCapability() {
        var snapshot = reportsSnapshotWithPlanValue(50)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200)))
            .override(hold(REPORTS, 2, EntitlementValue.Quantity.of(0)))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", REPORTS, NOW);
        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(decision.allowed()).isTrue(); // no off-value declared — 0 is a legitimate quantity (§5)
    }

    @Test
    void planBeatsASmallerGrant() {
        var snapshot = reportsSnapshotWithPlanValue(150)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(100)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(150));
    }

    @Test
    void unlimitedPlanCappedByAFiniteHold() {
        var snapshot = new SnapshotBuilder()
            .capability(quantityCapability(SEATS, EntitlementValue.Quantity.of(0)))
            .plan(new Plan("enterprise", "Enterprise", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("enterprise", SEATS, EntitlementValue.Quantity.unbounded()))
            .account(new AccountAssignment("acct_1", "enterprise"))
            .override(hold(SEATS, 1, EntitlementValue.Quantity.of(100)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", SEATS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(100));
    }

    @Test
    void tierWithNoOffValueIsAllowed() {
        var tiers = new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold")));
        var capability = new Capability(SUPPORT, "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("community", 0), Optional.empty(), tiers, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder()
            .capability(capability)
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", SUPPORT, NOW);
        assertThat(decision.value()).isEqualTo(new EntitlementValue.Tier("community", 0));
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void tierEqualToItsOffValueIsDisallowed() {
        var tiers = new TierOrder(List.of(
            new TierOrder.TierDefinition("none", 0, "None"),
            new TierOrder.TierDefinition("standard", 1, "Standard")));
        var capability = new Capability(SLA, "SLA", null, ValueType.TIER,
            new EntitlementValue.Tier("none", 0),
            Optional.of(new OffValue(new EntitlementValue.Tier("none", 0))),
            tiers, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder()
            .capability(capability)
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", SLA, NOW).allowed()).isFalse();
    }

    // Errors are errors, never denials (c19).

    @Test
    void unknownAccountThrows() {
        var snapshot = new SnapshotBuilder().capability(quantityCapability(REPORTS, EntitlementValue.Quantity.of(0))).build(1);
        assertThatThrownBy(() -> Resolver.resolve(snapshot, "acct_missing", REPORTS, NOW))
            .isInstanceOf(UnknownAccountException.class);
    }

    @Test
    void unknownCapabilityThrows() {
        var snapshot = new SnapshotBuilder().account(new AccountAssignment("acct_1", "free")).build(1);
        assertThatThrownBy(() -> Resolver.resolve(snapshot, "acct_1", REPORTS, NOW))
            .isInstanceOf(UnknownCapabilityException.class);
    }

    @Test
    void retiredCapabilityThrows() {
        var retired = new Capability(REPORTS, "Reports", null, ValueType.QUANTITY, EntitlementValue.Quantity.of(0),
            Optional.empty(), TierOrder.NONE, Capability.Status.RETIRED, NOW);
        var snapshot = new SnapshotBuilder().capability(retired).account(new AccountAssignment("acct_1", "free")).build(1);
        assertThatThrownBy(() -> Resolver.resolve(snapshot, "acct_1", REPORTS, NOW))
            .isInstanceOf(RetiredCapabilityException.class);
    }

    @Test
    void removingAGrantRestoresThePlanValueWithNoFurtherAction() {
        var withGrant = reportsSnapshotWithPlanValue(50).override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200))).build(1);
        var withoutGrant = reportsSnapshotWithPlanValue(50).build(2);

        assertThat(Resolver.resolve(withGrant, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(200));
        assertThat(Resolver.resolve(withoutGrant, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(50));
    }

    @Test
    void removingAHoldRestoresTheGrantValueWithNoFurtherAction() {
        var withHold = reportsSnapshotWithPlanValue(50)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200)))
            .override(hold(REPORTS, 2, EntitlementValue.Quantity.of(0)))
            .build(1);
        var withoutHold = reportsSnapshotWithPlanValue(50)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200)))
            .build(2);

        assertThat(Resolver.resolve(withHold, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(Resolver.resolve(withoutHold, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(200));
    }

    private static SnapshotBuilder reportsSnapshotWithPlanValue(long amount) {
        return new SnapshotBuilder()
            .capability(quantityCapability(REPORTS, EntitlementValue.Quantity.of(0)))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(amount)))
            .account(new AccountAssignment("acct_1", "pro"));
    }
}
