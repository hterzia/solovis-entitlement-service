package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

public record AccountDetailDto(
    String account, String name, String status, PlanInfo plan, long snapshotVersion,
    List<EntitlementRow> entitlements, List<OverrideRow> overrides
) {
    public record PlanInfo(String key, String name, String assignedAt, String assignedBy, String source) {}
    public record EntitlementRow(String capability, String area, boolean allowed, ValueDto value, String source, SourceDetail sourceDetail) {}
    public record SourceDetail(String overrideId, String reason, String planKey) {}
    public record OverrideRow(String id, String capability, String kind, ValueDto value, String reason,
        String createdBy, String createdAt, String effectNow) {}
}
