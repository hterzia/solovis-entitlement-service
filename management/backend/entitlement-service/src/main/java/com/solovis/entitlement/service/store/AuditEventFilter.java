package com.solovis.entitlement.service.store;

/**
 * Every field except {@code limit} is an optional filter; {@code null} means "no constraint on this field".
 * {@code beforeSeq} is the descending-order pagination cursor: only rows with {@code seq < beforeSeq} are returned.
 */
public record AuditEventFilter(
		Long accountId,
		Long planId,
		Long capabilityId,
		String actorId,
		String entityType,
		String occurredFrom,
		String occurredTo,
		Long beforeSeq,
		int limit) {
}
