package com.solovis.entitlement.service.api.dto;

import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import java.util.List;

public record CapabilityListResponseDto(List<CapabilityDescriptorDto> capabilities, long snapshotVersion) {}
