package com.solovis.entitlement.service.store;

public record CapabilityRow(
		Long id,
		String key,
		String area,
		String displayName,
		String description,
		String valueType,
		Boolean defaultBool,
		Long defaultQty,
		boolean defaultQtyUnlimited,
		String defaultTier,
		boolean hasOffValue,
		Long offQty,
		String offTier,
		String status,
		String retiredAt,
		String createdAt,
		String updatedAt) {
}
