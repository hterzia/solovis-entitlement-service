package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record PlanReassignRequest(@NotBlank String planKey, String source, String actor, String reason) {}
