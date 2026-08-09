package com.solovis.entitlement.service.store;

public record AccountOverrideRow(
		Long id,
		long accountId,
		long capabilityId,
		String kind,
		Boolean boolValue,
		Long qtyValue,
		boolean qtyUnlimited,
		String tierValue,
		String reason,
		String createdAt,
		String createdBy,
		String createdSource,
		String removedAt,
		String removedBy,
		String removedReason) {
}
