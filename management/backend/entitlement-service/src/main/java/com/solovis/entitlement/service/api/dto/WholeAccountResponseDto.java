package com.solovis.entitlement.service.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WholeAccountResponseDto(
    String account, String planKey, long snapshotVersion, String evaluatedAt, List<Entitlement> entitlements
) {
    public record Entitlement(String capability, boolean allowed, ValueDto value) {}
}
