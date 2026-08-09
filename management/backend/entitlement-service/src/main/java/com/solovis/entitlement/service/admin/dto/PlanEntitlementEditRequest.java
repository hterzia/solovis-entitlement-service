package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;
import java.util.Map;

public record PlanEntitlementEditRequest(Map<String, ValueDto> set, List<String> unset, String previewAccount, String previewToken) {
    public PlanEntitlementEditRequest {
        set = set != null ? set : Map.of();
        unset = unset != null ? unset : List.of();
    }
}
