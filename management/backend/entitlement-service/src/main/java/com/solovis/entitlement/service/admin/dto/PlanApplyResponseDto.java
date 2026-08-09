package com.solovis.entitlement.service.admin.dto;

public record PlanApplyResponseDto(
    String planKey, long affectedAccountCount, long snapshotVersion, long auditSeq, int changeVisibleEverywhereWithinSeconds
) {}
