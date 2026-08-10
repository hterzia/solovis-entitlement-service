package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Key/value facts the service remembers about itself across restarts (see {@code V2__service_state.sql}). */
@Repository
public class ServiceStateRepository {

	/** Fingerprint of the conformance vector set last announced to replicas. */
	public static final String CONFORMANCE_DIGEST = "conformance.digest";

	private final JdbcClient jdbcClient;

	public ServiceStateRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<String> find(String key) {
		return jdbcClient.sql("SELECT value FROM service_state WHERE key = :key")
				.param("key", key)
				.query(String.class)
				.optional();
	}

	public void put(String key, String value, String updatedAt) {
		jdbcClient.sql("""
				INSERT INTO service_state (key, value, updated_at) VALUES (:key, :value, :updatedAt)
				ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
				""")
				.param("key", key)
				.param("value", value)
				.param("updatedAt", updatedAt)
				.update();
	}
}
