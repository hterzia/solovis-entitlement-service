package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityKeyTest {

    @Test
    void derivesAreaFromThePrefixBeforeTheFirstDot() {
        assertThat(new CapabilityKey("export.parquet").area()).isEqualTo("export");
        assertThat(new CapabilityKey("integration.salesforce.write").area()).isEqualTo("integration");
    }

    @Test
    void rejectsAKeyWithNoDot() {
        assertThatThrownBy(() -> new CapabilityKey("apiaccess"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUppercaseAndEmpty() {
        assertThatThrownBy(() -> new CapabilityKey("Export.Parquet"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityKey(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalKeysAreEqual() {
        assertThat(new CapabilityKey("export.parquet")).isEqualTo(new CapabilityKey("export.parquet"));
    }
}
