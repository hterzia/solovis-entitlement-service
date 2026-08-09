package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountCreateRequest(@NotBlank String externalId, String name) {}
