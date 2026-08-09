package com.solovis.entitlement.service.admin.dto;

public record AuditEventDto(
    long seq, String occurredAt, Actor actor, String source, String entityType, String entityId, String action,
    String planKey, String account, String capability, Object before, Object after, String reason, Long affectedAccountCount
) {
    public record Actor(String id, String kind) {}
}
