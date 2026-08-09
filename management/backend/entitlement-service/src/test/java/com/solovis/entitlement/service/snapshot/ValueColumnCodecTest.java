package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueColumnCodecTest {

    @Test
    void roundTripsUnlimitedQuantity() {
        var columns = ValueColumnCodec.toColumns(EntitlementValue.Quantity.unbounded());
        assertThat(columns.qtyUnlimited()).isTrue();
        assertThat(columns.qtyValue()).isNull();

        var value = ValueColumnCodec.toValue(ValueType.QUANTITY, null, null, true, null, TierOrder.NONE);
        assertThat(value).isEqualTo(EntitlementValue.Quantity.unbounded());
    }

    @Test
    void roundTripsTierUsingCurrentOrdinal() {
        var tierOrder = new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold")));
        var columns = ValueColumnCodec.toColumns(new EntitlementValue.Tier("gold", 1));
        assertThat(columns.tierValue()).isEqualTo("gold");

        var value = ValueColumnCodec.toValue(ValueType.TIER, null, null, false, "gold", tierOrder);
        assertThat(value).isEqualTo(new EntitlementValue.Tier("gold", 1));
    }
}
