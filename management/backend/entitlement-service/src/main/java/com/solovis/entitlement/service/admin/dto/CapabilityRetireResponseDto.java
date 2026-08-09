package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import java.util.List;

public record CapabilityRetireResponseDto(CapabilityDescriptorDto capability, Usage usage) {
    public record Usage(List<String> plans, long liveOverrides) {}
}
