package com.solovis.entitlement.service.admin.dto;

public record PlanReassignResponseDto(String account, String planKey, long retainedOverrideCount, long snapshotVersion) {}
