package com.solovis.entitlement.core.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A capability's dotted, unique name. The substring before the first dot is its area (c40),
 * derived here rather than stored separately so it can never drift from the key.
 */
public record CapabilityKey(String value) implements Comparable<CapabilityKey> {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9_-]+)+$");

    public CapabilityKey {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Capability key '" + value + "' must match " + PATTERN.pattern());
        }
    }

    public String area() {
        return value.substring(0, value.indexOf('.'));
    }

    @Override
    public int compareTo(CapabilityKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
