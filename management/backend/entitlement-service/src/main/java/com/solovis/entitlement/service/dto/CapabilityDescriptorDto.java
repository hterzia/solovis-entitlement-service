package com.solovis.entitlement.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Returned wherever a caller needs to interpret values (contracts/README.md, "Capability descriptor"). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityDescriptorDto(
    String key,
    String area,
    String displayName,
    String description,
    String valueType,
    @JsonProperty("default") ValueDto defaultValue,
    ValueDto offValue,
    List<TierDto> tiers,
    String status
) {
    public record TierDto(String tier, int ordinal, String displayName) {}
}
