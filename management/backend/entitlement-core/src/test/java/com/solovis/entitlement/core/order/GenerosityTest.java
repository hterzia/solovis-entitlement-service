package com.solovis.entitlement.core.order;

import com.solovis.entitlement.core.model.EntitlementValue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerosityTest {

    @Test
    void switchOnIsMoreGenerousThanOff() {
        var off = new EntitlementValue.Switch(false);
        var on = new EntitlementValue.Switch(true);
        assertThat(Generosity.compare(off, on)).isNegative();
        assertThat(Generosity.mostGenerous(off, on)).isEqualTo(on);
        assertThat(Generosity.mostRestrictive(off, on)).isEqualTo(off);
    }

    @Test
    void largerQuantityIsMoreGenerous() {
        var fifty = EntitlementValue.Quantity.of(50);
        var twoHundred = EntitlementValue.Quantity.of(200);
        assertThat(Generosity.compare(fifty, twoHundred)).isNegative();
        assertThat(Generosity.mostGenerous(fifty, twoHundred)).isEqualTo(twoHundred);
    }

    @Test
    void unlimitedIsMoreGenerousThanAnyFiniteAmount() {
        var large = EntitlementValue.Quantity.of(1_000_000_000L);
        var unlimited = EntitlementValue.Quantity.unbounded();
        assertThat(Generosity.compare(large, unlimited)).isNegative();
        assertThat(Generosity.mostGenerous(large, unlimited)).isEqualTo(unlimited);
    }

    @Test
    void twoUnlimitedQuantitiesAreEqual() {
        assertThat(Generosity.compare(EntitlementValue.Quantity.unbounded(), EntitlementValue.Quantity.unbounded()))
            .isZero();
    }

    @Test
    void higherTierOrdinalIsMoreGenerous() {
        var community = new EntitlementValue.Tier("community", 0);
        var gold = new EntitlementValue.Tier("gold", 2);
        assertThat(Generosity.compare(community, gold)).isNegative();
        assertThat(Generosity.mostGenerous(community, gold)).isEqualTo(gold);
    }

    @Test
    void comparingDifferentValueTypesThrows() {
        assertThatThrownBy(() -> Generosity.compare(new EntitlementValue.Switch(true), EntitlementValue.Quantity.of(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueComparatorSortsAscendingByGenerosity() {
        var fifty = EntitlementValue.Quantity.of(50);
        var twoHundred = EntitlementValue.Quantity.of(200);
        assertThat(ValueComparator.INSTANCE.compare(fifty, twoHundred)).isNegative();
        assertThat(ValueComparator.INSTANCE.compare(twoHundred, fifty)).isPositive();
        assertThat(ValueComparator.INSTANCE.compare(fifty, EntitlementValue.Quantity.of(50))).isZero();
    }
}
