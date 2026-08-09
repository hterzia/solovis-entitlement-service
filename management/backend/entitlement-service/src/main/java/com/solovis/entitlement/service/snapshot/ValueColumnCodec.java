package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;

/** The bool_value/qty_value/qty_unlimited/tier_value column quartet <-> EntitlementValue (data-model.md "Value representation"). */
public final class ValueColumnCodec {

    private ValueColumnCodec() {}

    public record Columns(Boolean boolValue, Long qtyValue, boolean qtyUnlimited, String tierValue) {}

    public static Columns toColumns(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> new Columns(s.enabled(), null, false, null);
            case EntitlementValue.Quantity q -> q.unlimited()
                ? new Columns(null, null, true, null)
                : new Columns(null, q.amount(), false, null);
            case EntitlementValue.Tier t -> new Columns(null, null, false, t.tierKey());
        };
    }

    /** {@code tierOrder} supplies the authoritative ordinal for a stored tier_value — the columns never carry one. */
    public static EntitlementValue toValue(
        ValueType type, Boolean boolValue, Long qtyValue, boolean qtyUnlimited, String tierValue, TierOrder tierOrder) {
        return switch (type) {
            case SWITCH -> new EntitlementValue.Switch(Boolean.TRUE.equals(boolValue));
            case QUANTITY -> qtyUnlimited ? EntitlementValue.Quantity.unbounded() : EntitlementValue.Quantity.of(qtyValue);
            case TIER -> new EntitlementValue.Tier(tierValue, tierOrder.ordinalOf(tierValue)
                .orElseThrow(() -> new IllegalStateException(
                    "Stored tier '" + tierValue + "' is not declared by its capability's current tier order.")));
        };
    }
}
