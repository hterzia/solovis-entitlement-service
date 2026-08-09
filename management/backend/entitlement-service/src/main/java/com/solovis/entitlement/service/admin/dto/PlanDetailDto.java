package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import java.util.Map;

public record PlanDetailDto(String key, String name, String description, String status,
    boolean isDefaultForNewAccounts, long accountCount, Map<String, ValueDto> entitlements) {}
