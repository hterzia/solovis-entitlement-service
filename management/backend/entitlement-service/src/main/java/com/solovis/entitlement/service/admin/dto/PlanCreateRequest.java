package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record PlanCreateRequest(@NotBlank String key, @NotBlank String name, String description) {}
