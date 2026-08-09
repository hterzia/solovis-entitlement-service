package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record TierAppendRequest(@NotBlank String tier, @NotBlank String displayName) {}
