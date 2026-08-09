package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PlanRepository {

	private static final RowMapper<PlanRow> ROW_MAPPER = (rs, rowNum) -> new PlanRow(
			rs.getLong("id"),
			rs.getString("key"),
			rs.getString("name"),
			rs.getString("description"),
			rs.getString("status"),
			rs.getBoolean("is_default_for_new_accounts"),
			rs.getString("created_at"),
			rs.getString("updated_at"));

	private final JdbcClient jdbcClient;

	public PlanRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(PlanRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO plan (key, name, description, status, is_default_for_new_accounts, created_at, updated_at)
				VALUES (:key, :name, :description, :status, :defaultForNewAccounts, :createdAt, :updatedAt)
				""")
				.param("key", row.key())
				.param("name", row.name())
				.param("description", row.description())
				.param("status", row.status())
				.param("defaultForNewAccounts", row.defaultForNewAccounts() ? 1 : 0)
				.param("createdAt", row.createdAt())
				.param("updatedAt", row.updatedAt())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<PlanRow> findByKey(String key) {
		return jdbcClient.sql("SELECT * FROM plan WHERE key = :key")
				.param("key", key)
				.query(ROW_MAPPER)
				.optional();
	}

	public Optional<PlanRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM plan WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<PlanRow> findAll(String status) {
		if (status == null) {
			return jdbcClient.sql("SELECT * FROM plan ORDER BY key").query(ROW_MAPPER).list();
		}
		return jdbcClient.sql("SELECT * FROM plan WHERE status = :status ORDER BY key")
				.param("status", status)
				.query(ROW_MAPPER)
				.list();
	}

	public int update(long id, String name, String description, String updatedAt) {
		return jdbcClient.sql("""
				UPDATE plan SET name = :name, description = :description, updated_at = :updatedAt WHERE id = :id
				""")
				.param("id", id)
				.param("name", name)
				.param("description", description)
				.param("updatedAt", updatedAt)
				.update();
	}

	public boolean archive(long id, String updatedAt) {
		int rows = jdbcClient.sql("""
				UPDATE plan SET status = 'ARCHIVED', updated_at = :updatedAt WHERE id = :id AND status = 'ACTIVE'
				""")
				.param("id", id)
				.param("updatedAt", updatedAt)
				.update();
		return rows == 1;
	}

	public Optional<PlanRow> findDefault() {
		return jdbcClient.sql("SELECT * FROM plan WHERE is_default_for_new_accounts = 1")
				.query(ROW_MAPPER)
				.optional();
	}

	public void clearDefault(String updatedAt) {
		jdbcClient.sql("""
				UPDATE plan SET is_default_for_new_accounts = 0, updated_at = :updatedAt
				WHERE is_default_for_new_accounts = 1
				""")
				.param("updatedAt", updatedAt)
				.update();
	}

	public boolean setDefault(long id, String updatedAt) {
		int rows = jdbcClient.sql("""
				UPDATE plan SET is_default_for_new_accounts = 1, updated_at = :updatedAt
				WHERE id = :id AND status = 'ACTIVE'
				""")
				.param("id", id)
				.param("updatedAt", updatedAt)
				.update();
		return rows == 1;
	}

	public long countAccounts(long planId) {
		return jdbcClient.sql("SELECT COUNT(*) FROM account WHERE plan_id = :planId")
				.param("planId", planId)
				.query(Long.class)
				.single();
	}
}
