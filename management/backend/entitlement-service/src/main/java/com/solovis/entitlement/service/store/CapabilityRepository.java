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
public class CapabilityRepository {

	private static final RowMapper<CapabilityRow> ROW_MAPPER = (rs, rowNum) -> new CapabilityRow(
			rs.getLong("id"),
			rs.getString("key"),
			rs.getString("area"),
			rs.getString("display_name"),
			rs.getString("description"),
			rs.getString("value_type"),
			rs.getObject("default_bool") == null ? null : rs.getBoolean("default_bool"),
			rs.getObject("default_qty") == null ? null : rs.getLong("default_qty"),
			rs.getBoolean("default_qty_unlimited"),
			rs.getString("default_tier"),
			rs.getBoolean("has_off_value"),
			rs.getObject("off_qty") == null ? null : rs.getLong("off_qty"),
			rs.getString("off_tier"),
			rs.getString("status"),
			rs.getString("retired_at"),
			rs.getString("created_at"),
			rs.getString("updated_at"));

	private static final RowMapper<CapabilityTierRow> TIER_ROW_MAPPER = (rs, rowNum) -> new CapabilityTierRow(
			rs.getLong("capability_id"),
			rs.getString("tier_key"),
			rs.getInt("ordinal"),
			rs.getString("display_name"));

	private final JdbcClient jdbcClient;

	public CapabilityRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	static String deriveArea(String key) {
		int dot = key.indexOf('.');
		if (dot <= 0) {
			throw new IllegalArgumentException("Capability key '%s' has no area prefix".formatted(key));
		}
		return key.substring(0, dot);
	}

	public long insert(CapabilityRow row) {
		String area = deriveArea(row.key());
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO capability (
				    key, area, display_name, description, value_type,
				    default_bool, default_qty, default_qty_unlimited, default_tier,
				    has_off_value, off_qty, off_tier,
				    status, retired_at, created_at, updated_at
				) VALUES (
				    :key, :area, :displayName, :description, :valueType,
				    :defaultBool, :defaultQty, :defaultQtyUnlimited, :defaultTier,
				    :hasOffValue, :offQty, :offTier,
				    :status, :retiredAt, :createdAt, :updatedAt
				)
				""")
				.param("key", row.key())
				.param("area", area)
				.param("displayName", row.displayName())
				.param("description", row.description())
				.param("valueType", row.valueType())
				.param("defaultBool", row.defaultBool())
				.param("defaultQty", row.defaultQty())
				.param("defaultQtyUnlimited", row.defaultQtyUnlimited() ? 1 : 0)
				.param("defaultTier", row.defaultTier())
				.param("hasOffValue", row.hasOffValue() ? 1 : 0)
				.param("offQty", row.offQty())
				.param("offTier", row.offTier())
				.param("status", row.status())
				.param("retiredAt", row.retiredAt())
				.param("createdAt", row.createdAt())
				.param("updatedAt", row.updatedAt())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<CapabilityRow> findByKey(String key) {
		return jdbcClient.sql("SELECT * FROM capability WHERE key = :key")
				.param("key", key)
				.query(ROW_MAPPER)
				.optional();
	}

	public Optional<CapabilityRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM capability WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public boolean existsByKey(String key) {
		return jdbcClient.sql("SELECT COUNT(*) FROM capability WHERE key = :key")
				.param("key", key)
				.query(Integer.class)
				.single() > 0;
	}

	public List<CapabilityRow> findAll(String area, String status, String query) {
		StringBuilder sql = new StringBuilder("SELECT * FROM capability WHERE 1 = 1");
		if (area != null) {
			sql.append(" AND area = :area");
		}
		if (status != null) {
			sql.append(" AND status = :status");
		}
		if (query != null) {
			sql.append(" AND (key LIKE :query OR display_name LIKE :query)");
		}
		sql.append(" ORDER BY area, key");

		var spec = jdbcClient.sql(sql.toString());
		if (area != null) {
			spec = spec.param("area", area);
		}
		if (status != null) {
			spec = spec.param("status", status);
		}
		if (query != null) {
			spec = spec.param("query", "%" + query + "%");
		}
		return spec.query(ROW_MAPPER).list();
	}

	public int update(CapabilityRow row) {
		return jdbcClient.sql("""
				UPDATE capability SET
				    display_name = :displayName,
				    description = :description,
				    default_bool = :defaultBool,
				    default_qty = :defaultQty,
				    default_qty_unlimited = :defaultQtyUnlimited,
				    default_tier = :defaultTier,
				    has_off_value = :hasOffValue,
				    off_qty = :offQty,
				    off_tier = :offTier,
				    updated_at = :updatedAt
				WHERE id = :id
				""")
				.param("id", row.id())
				.param("displayName", row.displayName())
				.param("description", row.description())
				.param("defaultBool", row.defaultBool())
				.param("defaultQty", row.defaultQty())
				.param("defaultQtyUnlimited", row.defaultQtyUnlimited() ? 1 : 0)
				.param("defaultTier", row.defaultTier())
				.param("hasOffValue", row.hasOffValue() ? 1 : 0)
				.param("offQty", row.offQty())
				.param("offTier", row.offTier())
				.param("updatedAt", row.updatedAt())
				.update();
	}

	public boolean retire(long id, String retiredAt, String updatedAt) {
		int rows = jdbcClient.sql("""
				UPDATE capability SET status = 'RETIRED', retired_at = :retiredAt, updated_at = :updatedAt
				WHERE id = :id AND status = 'ACTIVE'
				""")
				.param("id", id)
				.param("retiredAt", retiredAt)
				.param("updatedAt", updatedAt)
				.update();
		return rows == 1;
	}

	public void insertTier(CapabilityTierRow row) {
		jdbcClient.sql("""
				INSERT INTO capability_tier (capability_id, tier_key, ordinal, display_name)
				VALUES (:capabilityId, :tierKey, :ordinal, :displayName)
				""")
				.param("capabilityId", row.capabilityId())
				.param("tierKey", row.tierKey())
				.param("ordinal", row.ordinal())
				.param("displayName", row.displayName())
				.update();
	}

	public List<CapabilityTierRow> findTiers(long capabilityId) {
		return jdbcClient.sql("SELECT * FROM capability_tier WHERE capability_id = :capabilityId ORDER BY ordinal")
				.param("capabilityId", capabilityId)
				.query(TIER_ROW_MAPPER)
				.list();
	}

	public Optional<Integer> findMaxOrdinal(long capabilityId) {
		// MAX() over zero matching rows still returns exactly one row, with a null value —
		// .single() rejects a null result, but .optional() correctly collapses both "no rows"
		// and "one row, null value" to Optional.empty() (verified empirically against 7.0.8).
		return jdbcClient.sql("SELECT MAX(ordinal) FROM capability_tier WHERE capability_id = :capabilityId")
				.param("capabilityId", capabilityId)
				.query(Integer.class)
				.optional();
	}

	public Optional<CapabilityTierRow> findTier(long capabilityId, String tierKey) {
		return jdbcClient.sql("""
				SELECT * FROM capability_tier WHERE capability_id = :capabilityId AND tier_key = :tierKey
				""")
				.param("capabilityId", capabilityId)
				.param("tierKey", tierKey)
				.query(TIER_ROW_MAPPER)
				.optional();
	}
}
