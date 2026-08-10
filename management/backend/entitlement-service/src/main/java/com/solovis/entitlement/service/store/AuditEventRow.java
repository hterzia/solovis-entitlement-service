package com.solovis.entitlement.service.store;

/**
 * One {@code audit_event} row.
 *
 * <p>{@code windowTransition} is {@code 'START'} or {@code 'EXPIRY'} on the rows the clock writes,
 * and null on every row a person caused. It is appended rather than placed among the others so
 * that every pre-existing positional construction keeps its meaning; {@link #operatorAct} is the
 * shape almost every caller wants.
 */
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
		Long affectedAccountCount,
		String windowTransition) {

	/** Something a person did. The schema requires windowTransition to be absent for these. */
	public static AuditEventRow operatorAct(
			Long seq, String occurredAt, String actorKind, String actorId, String source,
			String entityType, String entityId, String action, Long accountId, Long planId,
			Long capabilityId, String beforeJson, String afterJson, String reason,
			Long affectedAccountCount) {
		return new AuditEventRow(seq, occurredAt, actorKind, actorId, source, entityType, entityId,
				action, accountId, planId, capabilityId, beforeJson, afterJson, reason,
				affectedAccountCount, null);
	}
}
