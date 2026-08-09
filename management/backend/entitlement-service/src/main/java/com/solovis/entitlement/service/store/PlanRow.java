package com.solovis.entitlement.service.store;

public record PlanRow(
		Long id,
		String key,
		String name,
		String description,
		String status,
		boolean defaultForNewAccounts,
		String createdAt,
		String updatedAt) {
}
