package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OverrideCreateRequest(
    @NotBlank String capability, @NotBlank String kind, @NotNull ValueDto value, @NotBlank String reason
) {}
