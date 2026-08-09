package com.solovis.entitlement.service.store;

public record CapabilityTierRow(
		long capabilityId,
		String tierKey,
		int ordinal,
		String displayName) {
}
