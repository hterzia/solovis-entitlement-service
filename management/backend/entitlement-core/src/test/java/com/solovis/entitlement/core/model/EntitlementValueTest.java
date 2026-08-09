package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntitlementValueTest {

    @Test
    void switchCarriesAnEnabledFlagAndItsOwnType() {
        var value = new EntitlementValue.Switch(true);
        assertThat(value.enabled()).isTrue();
        assertThat(value.valueType()).isEqualTo(ValueType.SWITCH);
    }

    @Test
    void quantityOfCarriesAnAmount() {
        var value = EntitlementValue.Quantity.of(50);
        assertThat(value.amount()).isEqualTo(50);
        assertThat(value.unlimited()).isFalse();
        assertThat(value.valueType()).isEqualTo(ValueType.QUANTITY);
    }

    @Test
    void quantityUnlimitedIsADistinctVariantNotALargeNumber() {
        var value = EntitlementValue.Quantity.unbounded();
        assertThat(value.unlimited()).isTrue();
        assertThat(value.amount()).isZero(); // amount is not meaningful when unlimited — never Long.MAX_VALUE (c2)
    }

    @Test
    void quantityRejectsNegativeAmounts() {
        assertThatThrownBy(() -> new EntitlementValue.Quantity(-1, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityRejectsAnAmountAlongsideUnlimited() {
        assertThatThrownBy(() -> new EntitlementValue.Quantity(5, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierCarriesItsDeclaredOrdinal() {
        var value = new EntitlementValue.Tier("gold", 2);
        assertThat(value.tierKey()).isEqualTo("gold");
        assertThat(value.ordinal()).isEqualTo(2);
        assertThat(value.valueType()).isEqualTo(ValueType.TIER);
    }

    @Test
    void tierRejectsANegativeOrdinal() {
        assertThatThrownBy(() -> new EntitlementValue.Tier("gold", -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
