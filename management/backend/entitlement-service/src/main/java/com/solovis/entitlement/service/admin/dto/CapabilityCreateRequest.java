package com.solovis.entitlement.service.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solovis.entitlement.service.dto.ValueDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record CapabilityCreateRequest(
    @NotBlank @Pattern(regexp = "^[a-z0-9]+(\\.[a-z0-9_-]+)+$", message = "must be dotted, e.g. 'export.parquet'") String key,
    @NotBlank String displayName,
    String description,
    @NotBlank String valueType,
    @NotNull @JsonProperty("default") ValueDto defaultValue,
    ValueDto offValue,
    List<TierRequest> tiers
) {
    public record TierRequest(@NotBlank String tier, @NotBlank String displayName) {}
}
