package com.solovis.entitlement.service.audit;

import java.util.Set;

public record AuditEntry(
    Actor actor,
    String source,
    String entityType,
    String entityId,
    String action,
    Long accountId,
    Long planId,
    Long capabilityId,
    String beforeJson,
    String afterJson,
    String reason,
    Long affectedAccountCount
) {
    private static final Set<String> SOURCES = Set.of("UI", "BILLING", "API", "SEED");
    private static final Set<String> ENTITY_TYPES = Set.of(
        "CAPABILITY", "CAPABILITY_TIER", "PLAN", "PLAN_ENTITLEMENT", "ACCOUNT", "ACCOUNT_PLAN",
        "DEFAULT_PLAN", "OVERRIDE");
    private static final Set<String> ACTIONS = Set.of(
        "CREATE", "UPDATE", "RETIRE", "ARCHIVE", "REMOVE", "ASSIGN", "DESIGNATE");

    public AuditEntry {
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("Unknown audit source '" + source + "'.");
        }
        if (!ENTITY_TYPES.contains(entityType)) {
            throw new IllegalArgumentException("Unknown audit entity type '" + entityType + "'.");
        }
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unknown audit action '" + action + "'.");
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Actor actor;
        private String source = "UI";
        private String entityType;
        private String entityId;
        private String action;
        private Long accountId;
        private Long planId;
        private Long capabilityId;
        private String beforeJson;
        private String afterJson;
        private String reason;
        private Long affectedAccountCount;

        public Builder actor(Actor actor) { this.actor = actor; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder entityType(String entityType) { this.entityType = entityType; return this; }
        public Builder entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder accountId(Long accountId) { this.accountId = accountId; return this; }
        public Builder planId(Long planId) { this.planId = planId; return this; }
        public Builder capabilityId(Long capabilityId) { this.capabilityId = capabilityId; return this; }
        public Builder beforeJson(String beforeJson) { this.beforeJson = beforeJson; return this; }
        public Builder afterJson(String afterJson) { this.afterJson = afterJson; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder affectedAccountCount(Long affectedAccountCount) { this.affectedAccountCount = affectedAccountCount; return this; }

        public AuditEntry build() {
            return new AuditEntry(actor, source, entityType, entityId, action, accountId, planId,
                capabilityId, beforeJson, afterJson, reason, affectedAccountCount);
        }
    }
}
