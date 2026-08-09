package com.solovis.entitlement.core.model;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityTest {

    private static final TierOrder SUPPORT_TIERS = new TierOrder(List.of(
        new TierOrder.TierDefinition("community", 0, "Community"),
        new TierOrder.TierDefinition("gold", 1, "Gold")
    ));

    @Test
    void areaIsDerivedFromTheKey() {
        var capability = switchCapability("api.access");
        assertThat(capability.area()).isEqualTo("api");
    }

    @Test
    void switchDefaultMustMatchDeclaredValueType() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            EntitlementValue.Quantity.of(1), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void switchCapabilityMayNotDeclareAnOffValue() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.of(new OffValue(new EntitlementValue.Switch(false))),
            TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void switchOffValueIsInherentlyFalse() {
        var capability = switchCapability("api.access");
        assertThat(capability.effectiveOffValue()).contains(new EntitlementValue.Switch(false));
    }

    @Test
    void quantityOffValueMustBeZeroWhenDeclared() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(50), Optional.of(new OffValue(EntitlementValue.Quantity.of(1))),
            TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityOffValueMayNeverBeUnlimited() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(50), Optional.of(new OffValue(EntitlementValue.Quantity.unbounded())),
            TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityWithNoDeclaredOffValueHasNoEffectiveOffValue() {
        var capability = new Capability(
            new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(50), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        assertThat(capability.effectiveOffValue()).isEmpty();
    }

    @Test
    void tierCapabilityRequiresAtLeastTwoTiers() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("support.level"), "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("community", 0), Optional.empty(),
            new TierOrder(List.of(new TierOrder.TierDefinition("community", 0, "Community"))),
            Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierDefaultMustBeADeclaredTier() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("support.level"), "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("platinum", 9), Optional.empty(), SUPPORT_TIERS,
            Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierOffValueMustBeADeclaredTier() {
        var capability = new Capability(
            new CapabilityKey("support.level"), "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("community", 0),
            Optional.of(new OffValue(new EntitlementValue.Tier("community", 0))),
            SUPPORT_TIERS, Capability.Status.ACTIVE, null);
        assertThat(capability.effectiveOffValue()).contains(new EntitlementValue.Tier("community", 0));
    }

    @Test
    void retiredRequiresARetiredAtTimestamp() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.RETIRED, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isRetiredReflectsStatus() {
        var capability = switchCapability("api.access");
        assertThat(capability.isRetired()).isFalse();
    }

    private static Capability switchCapability(String key) {
        return new Capability(
            new CapabilityKey(key), "Display name", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.ACTIVE, null);
    }
}
