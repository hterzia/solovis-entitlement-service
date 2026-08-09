package com.solovis.entitlement.core.model;

import java.util.Objects;

/**
 * The effective value of a capability. Sealed so the resolver's comparison is exhaustively
 * checked at compile time — a fourth variant cannot be added without every switch expression
 * being revisited.
 */
public sealed interface EntitlementValue
    permits EntitlementValue.Switch, EntitlementValue.Quantity, EntitlementValue.Tier {

    ValueType valueType();

    record Switch(boolean enabled) implements EntitlementValue {
        @Override
        public ValueType valueType() {
            return ValueType.SWITCH;
        }
    }

    /**
     * Either {@code amount} or {@code unlimited}, never both and never neither (c2).
     * {@code unlimited} is a distinct variant, not {@code Long.MAX_VALUE} — a large number would
     * leak into any serialised form the moment it was written down.
     */
    record Quantity(long amount, boolean unlimited) implements EntitlementValue {

        public Quantity {
            if (unlimited && amount != 0) {
                throw new IllegalArgumentException("An unlimited quantity does not carry an amount.");
            }
            if (!unlimited && amount < 0) {
                throw new IllegalArgumentException("A quantity amount must not be negative.");
            }
        }

        public static Quantity of(long amount) {
            return new Quantity(amount, false);
        }

        public static Quantity unbounded() {
            return new Quantity(0, true);
        }

        @Override
        public ValueType valueType() {
            return ValueType.QUANTITY;
        }
    }

    /**
     * {@code ordinal} travels with the value (not just the key) so a caller can answer
     * "at least tier X" without a second call to the registry (c3).
     */
    record Tier(String tierKey, int ordinal) implements EntitlementValue {

        public Tier {
            Objects.requireNonNull(tierKey, "tierKey");
            if (ordinal < 0) {
                throw new IllegalArgumentException("Tier ordinal must not be negative.");
            }
        }

        @Override
        public ValueType valueType() {
            return ValueType.TIER;
        }
    }
}
