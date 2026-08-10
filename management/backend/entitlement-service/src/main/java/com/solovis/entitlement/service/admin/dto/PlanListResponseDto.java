package com.solovis.entitlement.service.admin.dto;

import java.util.List;

public record PlanListResponseDto(List<PlanSummaryDto> plans, long snapshotVersion) {}
