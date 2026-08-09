package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.service.store.CapabilityRow;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowMappersTest {

    @Test
    void toCapabilityMapsAQuantityCapabilityWithAZeroOffValue() {
        var row = new CapabilityRow(7L, "reports.monthly", "reports", "Monthly reports", null, "QUANTITY",
            null, 0L, false, null, true, 0L, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");

        var capability = RowMappers.toCapability(row, List.of());

        assertThat(capability.key().value()).isEqualTo("reports.monthly");
        assertThat(capability.defaultValue()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(capability.offValue()).isPresent();
        assertThat(capability.offValue().get().value()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(capability.isRetired()).isFalse();
    }
}
