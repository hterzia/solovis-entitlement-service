package com.solovis.entitlement.core.model;

import java.util.Objects;

/** One capability value set by one plan (spec §3.2). Its absence is what makes a plan partial (c4). */
public record PlanEntitlement(String planKey, CapabilityKey capabilityKey, EntitlementValue value) {

    public PlanEntitlement {
        Objects.requireNonNull(planKey, "planKey");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(value, "value");
    }
}
