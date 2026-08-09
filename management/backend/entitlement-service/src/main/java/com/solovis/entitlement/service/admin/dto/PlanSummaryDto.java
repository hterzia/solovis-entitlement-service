package com.solovis.entitlement.service.admin.dto;

public record PlanSummaryDto(String key, String name, String status, boolean isDefaultForNewAccounts,
    long accountCount, long entitlementCount) {}
