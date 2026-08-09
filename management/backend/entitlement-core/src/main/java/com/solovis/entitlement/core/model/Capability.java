package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A registered, evaluable thing an account may be allowed to do (spec §3.1). Nothing is
 * evaluable unless declared here — that is what stops ad-hoc checks creeping back in.
 */
public record Capability(
    CapabilityKey key,
    String displayName,
    String description,
    ValueType valueType,
    EntitlementValue defaultValue,
    Optional<OffValue> offValue,
    TierOrder tierOrder,
    Status status,
    Instant retiredAt
) {

    public enum Status { ACTIVE, RETIRED }

    public Capability {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(offValue, "offValue");
        Objects.requireNonNull(tierOrder, "tierOrder");
        Objects.requireNonNull(status, "status");

        if (defaultValue.valueType() != valueType) {
            throw new IllegalArgumentException(
                "Default value type " + defaultValue.valueType() + " does not match declared type " + valueType);
        }
        validateOffValue(valueType, offValue);
        validateTierOrder(valueType, tierOrder, defaultValue, offValue);
        if (status == Status.RETIRED && retiredAt == null) {
            throw new IllegalArgumentException("A retired capability must carry retiredAt.");
        }
        if (status == Status.ACTIVE && retiredAt != null) {
            throw new IllegalArgumentException("An active capability must not carry retiredAt.");
        }
    }

    private static void validateOffValue(ValueType valueType, Optional<OffValue> offValue) {
        if (offValue.isEmpty()) {
            return;
        }
        var declared = offValue.get().value();
        if (valueType == ValueType.SWITCH) {
            throw new IllegalArgumentException("A SWITCH capability may not declare an off-value; it is always false.");
        }
        if (declared.valueType() != valueType) {
            throw new IllegalArgumentException(
                "Off-value type " + declared.valueType() + " does not match declared type " + valueType);
        }
        if (valueType == ValueType.QUANTITY) {
            var quantity = (EntitlementValue.Quantity) declared;
            if (quantity.unlimited() || quantity.amount() != 0) {
                throw new IllegalArgumentException("A QUANTITY off-value, when declared, must be exactly 0.");
            }
        }
    }

    private static void validateTierOrder(
        ValueType valueType, TierOrder tierOrder, EntitlementValue defaultValue, Optional<OffValue> offValue) {
        if (valueType == ValueType.TIER) {
            if (tierOrder.tiers().size() < 2) {
                throw new IllegalArgumentException("A TIER capability must declare at least two tiers.");
            }
            var defaultTier = (EntitlementValue.Tier) defaultValue;
            requireDeclaredWithMatchingOrdinal(tierOrder, defaultTier, "Default");
            offValue.ifPresent(off -> {
                var offTier = (EntitlementValue.Tier) off.value();
                requireDeclaredWithMatchingOrdinal(tierOrder, offTier, "Off-value");
            });
        } else if (!tierOrder.tiers().isEmpty()) {
            throw new IllegalArgumentException("Only a TIER capability may declare tiers.");
        }
    }

    private static void requireDeclaredWithMatchingOrdinal(TierOrder tierOrder, EntitlementValue.Tier tier, String label) {
        var declaredOrdinal = tierOrder.ordinalOf(tier.tierKey());
        if (declaredOrdinal.isEmpty()) {
            throw new IllegalArgumentException(label + " tier '" + tier.tierKey() + "' is not declared.");
        }
        if (declaredOrdinal.getAsInt() != tier.ordinal()) {
            throw new IllegalArgumentException(
                label + " tier '" + tier.tierKey() + "' carries ordinal " + tier.ordinal()
                    + " but the capability declares it as " + declaredOrdinal.getAsInt() + ".");
        }
    }

    public String area() {
        return key.area();
    }

    public boolean isRetired() {
        return status == Status.RETIRED;
    }

    /**
     * The value meaning "not available", folding in the SWITCH rule that is never stored:
     * {@code false} is always SWITCH's off-value (spec §5 table).
     */
    public Optional<EntitlementValue> effectiveOffValue() {
        if (valueType == ValueType.SWITCH) {
            return Optional.of(new EntitlementValue.Switch(false));
        }
        return offValue.map(OffValue::value);
    }
}
