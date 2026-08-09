package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OffValueTest {

    @Test
    void wrapsAValue() {
        var offValue = new OffValue(EntitlementValue.Quantity.of(0));
        assertThat(offValue.value()).isEqualTo(EntitlementValue.Quantity.of(0));
    }

    @Test
    void rejectsANullValue() {
        assertThatThrownBy(() -> new OffValue(null)).isInstanceOf(NullPointerException.class);
    }
}
