package com.solovis.entitlement.service.admin.dto;

import java.util.List;

public record MetaResponseDto(int changeVisibleEverywhereWithinSeconds, int answerReuseMaxSeconds,
    long snapshotVersion, List<String> capabilityAreas, boolean askEnabled) {}
