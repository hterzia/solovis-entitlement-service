package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.EntitlementValue;

/** Renders an EntitlementValue as the short human-readable text preview/audit notes quote (e.g. "false", "50", "unlimited", "gold"). */
public final class ValueText {

    private ValueText() {}

    public static String describe(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> String.valueOf(s.enabled());
            case EntitlementValue.Quantity q -> q.unlimited() ? "unlimited" : String.valueOf(q.amount());
            case EntitlementValue.Tier t -> t.tierKey();
        };
    }
}
