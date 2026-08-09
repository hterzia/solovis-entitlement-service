package com.solovis.entitlement.core.model;

import java.util.Objects;

/**
 * A capability's declared "not-available" value (spec §5). SWITCH never has one here — its
 * off-value is {@code false}, inherently, and is never stored (see {@link Capability}).
 */
public record OffValue(EntitlementValue value) {

    public OffValue {
        Objects.requireNonNull(value, "value");
    }
}
