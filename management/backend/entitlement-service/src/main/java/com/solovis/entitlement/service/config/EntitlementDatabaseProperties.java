package com.solovis.entitlement.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entitlement.database")
public record EntitlementDatabaseProperties(
		String path,
		int writePoolSize,
		int readPoolSize,
		int busyTimeoutMillis) {

	public EntitlementDatabaseProperties {
		if (writePoolSize <= 0) {
			throw new IllegalArgumentException("entitlement.database.write-pool-size must be positive");
		}
		if (writePoolSize != 1) {
			throw new IllegalArgumentException(
					"entitlement.database.write-pool-size must be exactly 1: SnapshotPublisher's "
							+ "read-modify-write of the in-memory snapshot depends on write serialization");
		}
		if (readPoolSize <= 0) {
			throw new IllegalArgumentException("entitlement.database.read-pool-size must be positive");
		}
	}

	String jdbcUrl() {
		return "jdbc:sqlite:%s?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=%d&temp_store=MEMORY"
				.formatted(path, busyTimeoutMillis);
	}
}
