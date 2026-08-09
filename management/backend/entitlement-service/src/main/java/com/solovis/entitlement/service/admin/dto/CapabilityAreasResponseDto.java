package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import java.util.List;

/** {@code GET /admin/v1/capabilities?groupBy=area} — the same descriptor list grouped by area. */
public record CapabilityAreasResponseDto(List<AreaGroup> areas, long snapshotVersion) {
    public record AreaGroup(String area, List<CapabilityDescriptorDto> capabilities) {}
}
