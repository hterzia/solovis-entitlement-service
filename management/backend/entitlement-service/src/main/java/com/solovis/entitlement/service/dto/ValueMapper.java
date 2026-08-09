package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import java.util.Map;

/** Converts between the wire {@link ValueDto} and the core {@link EntitlementValue}. */
public final class ValueMapper {

    private ValueMapper() {}

    public static ValueDto toDto(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> new ValueDto("SWITCH", s.enabled(), null, null, null, null);
            case EntitlementValue.Quantity q -> q.unlimited()
                ? new ValueDto("QUANTITY", null, null, true, null, null)
                : new ValueDto("QUANTITY", null, q.amount(), null, null, null);
            case EntitlementValue.Tier t -> new ValueDto("TIER", null, null, null, t.tierKey(), t.ordinal());
        };
    }

    /**
     * Validates {@code dto} against {@code capability}'s declared shape (README.md "Value
     * encoding"; c1). `ordinal` is accepted but ignored on a request — `tier` is authoritative,
     * so the returned value always carries the capability's own declared ordinal for that key.
     */
    public static EntitlementValue fromDto(ValueDto dto, Capability capability) {
        ValueType declaredType = requireType(dto, capability);
        return switch (declaredType) {
            case SWITCH -> {
                if (dto.enabled() == null) {
                    throw validationFailed("A SWITCH value requires 'enabled'.");
                }
                yield new EntitlementValue.Switch(dto.enabled());
            }
            case QUANTITY -> {
                boolean hasAmount = dto.amount() != null;
                boolean hasUnlimited = Boolean.TRUE.equals(dto.unlimited());
                if (hasAmount == hasUnlimited) {
                    throw validationFailed("A QUANTITY value must carry exactly one of 'amount' or 'unlimited'.");
                }
                yield hasUnlimited ? EntitlementValue.Quantity.unbounded() : EntitlementValue.Quantity.of(dto.amount());
            }
            case TIER -> {
                if (dto.tier() == null) {
                    throw validationFailed("A TIER value requires 'tier'.");
                }
                var ordinal = capability.tierOrder().ordinalOf(dto.tier());
                if (ordinal.isEmpty()) {
                    throw new EntitlementApiException(ErrorCode.UNKNOWN_TIER,
                        "Tier '" + dto.tier() + "' is not declared by capability '" + capability.key() + "'.",
                        Map.of("capability", capability.key().value(), "tier", dto.tier()));
                }
                yield new EntitlementValue.Tier(dto.tier(), ordinal.getAsInt());
            }
        };
    }

    private static ValueType requireType(ValueDto dto, Capability capability) {
        ValueType declared;
        try {
            declared = ValueType.valueOf(dto.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw validationFailed("Unknown value type '" + dto.type() + "'.");
        }
        if (declared != capability.valueType()) {
            throw new EntitlementApiException(ErrorCode.VALUE_TYPE_MISMATCH,
                "Value type " + declared + " does not match capability '" + capability.key()
                    + "' (" + capability.valueType() + ").",
                Map.of("capability", capability.key().value()));
        }
        return declared;
    }

    private static EntitlementApiException validationFailed(String detail) {
        return new EntitlementApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
