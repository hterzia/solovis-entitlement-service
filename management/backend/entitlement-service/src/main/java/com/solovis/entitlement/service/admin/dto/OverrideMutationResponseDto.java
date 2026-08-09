package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.api.dto.DecisionResponseDto;

public record OverrideMutationResponseDto(
    String overrideId, DecisionResponseDto decision, long snapshotVersion, int changeVisibleEverywhereWithinSeconds
) {}
