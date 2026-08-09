package com.solovis.entitlement.service.store;

public record AuditEventRow(
		Long seq,
		String occurredAt,
		String actorKind,
		String actorId,
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
		Long affectedAccountCount) {
}
