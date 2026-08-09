package com.solovis.entitlement.service.store;

public record SnapshotVersionRow(
		Long version,
		String publishedAt,
		long lastAuditSeq,
		String deltaJson) {
}
