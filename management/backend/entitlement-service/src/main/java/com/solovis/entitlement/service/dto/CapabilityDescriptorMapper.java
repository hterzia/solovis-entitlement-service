package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.Capability;

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
}
