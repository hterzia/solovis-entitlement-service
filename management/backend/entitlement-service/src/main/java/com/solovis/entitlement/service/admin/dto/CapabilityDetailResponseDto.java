package com.solovis.entitlement.service.admin.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;

/**
 * GET /admin/v1/capabilities/{key} — the descriptor's own fields at the top level (admin-api.md:
 * "One capability, plus where it is used"), with `usage` alongside. Unwrapped rather than nested
 * under a `capability` key so a caller reading `displayName`/`valueType`/... sees the same shape
 * here as from every other capability-returning endpoint except retire.
 */
public record CapabilityDetailResponseDto(
    @JsonUnwrapped CapabilityDescriptorDto descriptor,
    CapabilityRetireResponseDto.Usage usage
) {}
