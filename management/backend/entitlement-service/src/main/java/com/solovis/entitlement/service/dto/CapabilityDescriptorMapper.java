package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class CapabilityDescriptorMapper {

    private CapabilityDescriptorMapper() {}

    public static CapabilityDescriptorDto toDescriptor(Capability capability) {
        var tiers = capability.tierOrder().tiers().stream()
            .map(t -> new CapabilityDescriptorDto.TierDto(t.tierKey(), t.ordinal(), t.displayName()))
            .toList();
        return new CapabilityDescriptorDto(
            capability.key().value(),
            capability.area(),
            capability.displayName(),
            capability.description(),
            capability.valueType().name(),
            ValueMapper.toDto(capability.defaultValue()),
            capability.offValue().map(ov -> ValueMapper.toDto(ov.value())).orElse(null),
            tiers.isEmpty() ? null : tiers,
            capability.status().name());
    }

    /**
     * The inverse, for point-in-time: {@code audit_event.after_json} stores a whole descriptor on
     * every capability write, which makes "what was this capability's default and off-value on 14
     * March" answerable from the record rather than from a value table nobody kept (002 c23).
     *
     * <p>{@code retiredAt} comes in separately because the descriptor does not carry it — the wire
     * shape says only {@code status}. The historical assembler passes the {@code occurred_at} of
     * the RETIRE entry, which is both the right instant and the one c28 reports as
     * {@code capabilityRetiredSince}.
     *
     * <p>Values are built against the tier order read from the same descriptor rather than through
     * {@link ValueMapper#fromDto}, which needs a {@link Capability} that does not exist yet.
     */
    public static Capability fromDescriptor(CapabilityDescriptorDto dto, Instant retiredAt) {
        var valueType = ValueType.valueOf(dto.valueType());
        var tierOrder = dto.tiers() == null || dto.tiers().isEmpty()
            ? TierOrder.NONE
            : new TierOrder(dto.tiers().stream()
                .map(t -> new TierOrder.TierDefinition(t.tier(), t.ordinal(), t.displayName()))
                .toList());
        var status = Capability.Status.valueOf(dto.status());
        return new Capability(
            new CapabilityKey(dto.key()),
            dto.displayName(),
            dto.description(),
            valueType,
            toValue(dto.defaultValue(), valueType, tierOrder),
            Optional.ofNullable(dto.offValue()).map(off -> new OffValue(toValue(off, valueType, tierOrder))),
            tierOrder,
            status,
            status == Capability.Status.RETIRED ? retiredAt : null);
    }

    private static EntitlementValue toValue(ValueDto dto, ValueType valueType, TierOrder tierOrder) {
        return switch (valueType) {
            case SWITCH -> new EntitlementValue.Switch(Boolean.TRUE.equals(dto.enabled()));
            case QUANTITY -> Boolean.TRUE.equals(dto.unlimited())
                ? EntitlementValue.Quantity.unbounded()
                : EntitlementValue.Quantity.of(dto.amount());
            case TIER -> {
                // The stored ordinal is authoritative: tiers may only ever be appended above the
                // max, never inserted between, so an ordinal recorded then still means then.
                int ordinal = dto.ordinal() != null
                    ? dto.ordinal()
                    : tierOrder.ordinalOf(dto.tier()).orElseThrow(() -> new IllegalStateException(
                        "Tier '" + dto.tier() + "' is not declared by the descriptor it was recorded with."));
                yield new EntitlementValue.Tier(dto.tier(), ordinal);
            }
        };
    }

    /** Rebuilds the tier list alone, for callers that only need the order. */
    public static List<TierOrder.TierDefinition> tiersOf(CapabilityDescriptorDto dto) {
        return dto.tiers() == null ? List.of()
            : dto.tiers().stream().map(t -> new TierOrder.TierDefinition(t.tier(), t.ordinal(), t.displayName())).toList();
    }
}
