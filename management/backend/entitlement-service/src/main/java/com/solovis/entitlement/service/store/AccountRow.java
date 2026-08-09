package com.solovis.entitlement.service.store;

public record AccountRow(
		Long id,
		String externalId,
		String name,
		long planId,
		String planAssignedAt,
		String planAssignmentSource,
		String planAssignmentActor,
		String status,
		String createdAt,
		String updatedAt) {
}
