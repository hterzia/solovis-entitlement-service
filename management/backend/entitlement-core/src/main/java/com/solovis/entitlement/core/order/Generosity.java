package com.solovis.entitlement.core.order;

import com.solovis.entitlement.core.model.EntitlementValue;

/**
 * The total order §4 needs to define "most generous" and "most restrictive". One rule per
 * value type; comparing across types is a caller bug, not a value to be ranked.
 */
public final class Generosity {

    private Generosity() {}

    public static int compare(EntitlementValue a, EntitlementValue b) {
        if (a.valueType() != b.valueType()) {
            throw new IllegalArgumentException(
                "Cannot compare values of different types: " + a.valueType() + " vs " + b.valueType());
        }
        return switch (a) {
            case EntitlementValue.Switch sa ->
                Boolean.compare(sa.enabled(), ((EntitlementValue.Switch) b).enabled());
            case EntitlementValue.Quantity qa -> compareQuantity(qa, (EntitlementValue.Quantity) b);
            case EntitlementValue.Tier ta -> Integer.compare(ta.ordinal(), ((EntitlementValue.Tier) b).ordinal());
        };
    }

    private static int compareQuantity(EntitlementValue.Quantity a, EntitlementValue.Quantity b) {
        if (a.unlimited() && b.unlimited()) {
            return 0;
        }
        if (a.unlimited()) {
            return 1;
        }
        if (b.unlimited()) {
            return -1;
        }
        return Long.compare(a.amount(), b.amount());
    }

    public static EntitlementValue mostGenerous(EntitlementValue a, EntitlementValue b) {
        return compare(a, b) >= 0 ? a : b;
    }

    public static EntitlementValue mostRestrictive(EntitlementValue a, EntitlementValue b) {
        return compare(a, b) <= 0 ? a : b;
    }
}
