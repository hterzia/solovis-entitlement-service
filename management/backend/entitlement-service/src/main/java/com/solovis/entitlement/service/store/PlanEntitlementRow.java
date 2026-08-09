package com.solovis.entitlement.service.store;

public record PlanEntitlementRow(
		long planId,
		long capabilityId,
		Boolean boolValue,
		Long qtyValue,
		boolean qtyUnlimited,
		String tierValue,
		String updatedAt) {
}
