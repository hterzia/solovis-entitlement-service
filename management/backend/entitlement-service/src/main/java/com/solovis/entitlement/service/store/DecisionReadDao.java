package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The only class that talks to the read pool for decision-path SQL. */
@Component
public class DecisionReadDao {

	private static final RowMapper<SnapshotVersionRow> SNAPSHOT_VERSION_ROW_MAPPER = (rs, rowNum) -> new SnapshotVersionRow(
			rs.getLong("version"),
			rs.getString("published_at"),
			rs.getLong("last_audit_seq"),
			rs.getString("delta_json"));

	private static final RowMapper<AccountRow> ACCOUNT_ROW_MAPPER = (rs, rowNum) -> new AccountRow(
			rs.getLong("id"),
			rs.getString("external_id"),
			rs.getString("name"),
			rs.getLong("plan_id"),
			rs.getString("plan_assigned_at"),
			rs.getString("plan_assignment_source"),
			rs.getString("plan_assignment_actor"),
			rs.getString("status"),
			rs.getString("created_at"),
			rs.getString("updated_at"));

	private static final RowMapper<PlanRow> PLAN_ROW_MAPPER = (rs, rowNum) -> new PlanRow(
			rs.getLong("id"),
			rs.getString("key"),
			rs.getString("name"),
			rs.getString("description"),
			rs.getString("status"),
			rs.getBoolean("is_default_for_new_accounts"),
			rs.getString("created_at"),
			rs.getString("updated_at"));

	private static final RowMapper<CapabilityRow> CAPABILITY_ROW_MAPPER = (rs, rowNum) -> new CapabilityRow(
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

	private static final RowMapper<PlanEntitlementRow> PLAN_ENTITLEMENT_ROW_MAPPER = (rs, rowNum) -> new PlanEntitlementRow(
			rs.getLong("plan_id"),
			rs.getLong("capability_id"),
			rs.getObject("bool_value") == null ? null : rs.getBoolean("bool_value"),
			rs.getObject("qty_value") == null ? null : rs.getLong("qty_value"),
			rs.getBoolean("qty_unlimited"),
			rs.getString("tier_value"),
			rs.getString("updated_at"));

	private static final RowMapper<AccountOverrideRow> ACCOUNT_OVERRIDE_ROW_MAPPER = (rs, rowNum) -> new AccountOverrideRow(
			rs.getLong("id"),
			rs.getLong("account_id"),
			rs.getLong("capability_id"),
			rs.getString("kind"),
			rs.getObject("bool_value") == null ? null : rs.getBoolean("bool_value"),
			rs.getObject("qty_value") == null ? null : rs.getLong("qty_value"),
			rs.getBoolean("qty_unlimited"),
			rs.getString("tier_value"),
			rs.getString("reason"),
			rs.getString("created_at"),
			rs.getString("created_by"),
			rs.getString("created_source"),
			rs.getString("removed_at"),
			rs.getString("removed_by"),
			rs.getString("removed_reason"));

	private final JdbcClient jdbcClient;

	public DecisionReadDao(@Qualifier("entitlementReadJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long latestVersion() {
		return jdbcClient.sql("SELECT * FROM snapshot_version ORDER BY version DESC LIMIT 1")
				.query(SNAPSHOT_VERSION_ROW_MAPPER)
				.optional()
				.map(SnapshotVersionRow::version)
				.orElse(0L);
	}

	public Optional<AccountRow> account(String externalId) {
		return jdbcClient.sql("SELECT * FROM account WHERE external_id = :externalId AND status = 'ACTIVE'")
				.param("externalId", externalId)
				.query(ACCOUNT_ROW_MAPPER)
				.optional();
	}

	public Optional<String> planKeyById(long planId) {
		return jdbcClient.sql("SELECT * FROM plan WHERE id = :id")
				.param("id", planId)
				.query(PLAN_ROW_MAPPER)
				.optional()
				.map(PlanRow::key);
	}

	public Optional<CapabilityRow> capabilityByKey(String key) {
		return jdbcClient.sql("SELECT * FROM capability WHERE key = :key")
				.param("key", key)
				.query(CAPABILITY_ROW_MAPPER)
				.optional();
	}

	public List<CapabilityTierRow> tiers(long capabilityId) {
		return jdbcClient.sql("SELECT * FROM capability_tier WHERE capability_id = :capabilityId ORDER BY ordinal")
				.param("capabilityId", capabilityId)
				.query(TIER_ROW_MAPPER)
				.list();
	}

	public Optional<PlanEntitlementRow> planEntitlement(long planId, long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM plan_entitlement WHERE plan_id = :planId AND capability_id = :capabilityId
				""")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.query(PLAN_ENTITLEMENT_ROW_MAPPER)
				.optional();
	}

	public List<AccountOverrideRow> liveOverrides(long accountId, long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE account_id = :accountId AND capability_id = :capabilityId AND removed_at IS NULL
				ORDER BY id
				""")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.query(ACCOUNT_OVERRIDE_ROW_MAPPER)
				.list();
	}

	public List<AccountOverrideRow> liveOverridesForAccount(long accountId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override WHERE account_id = :accountId AND removed_at IS NULL
				ORDER BY capability_id, id
				""")
				.param("accountId", accountId)
				.query(ACCOUNT_OVERRIDE_ROW_MAPPER)
				.list();
	}

	public List<CapabilityRow> activeCapabilities() {
		return allCapabilities(null, "ACTIVE", null);
	}

	public List<CapabilityRow> allCapabilities(String area, String status, String query) {
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
		return spec.query(CAPABILITY_ROW_MAPPER).list();
	}

	public Map<Long, List<CapabilityTierRow>> allTiers() {
		List<CapabilityTierRow> rows = jdbcClient.sql("SELECT * FROM capability_tier ORDER BY capability_id, ordinal")
				.query(TIER_ROW_MAPPER)
				.list();
		return rows.stream().collect(Collectors.groupingBy(CapabilityTierRow::capabilityId, LinkedHashMap::new, Collectors.toList()));
	}

	public List<PlanEntitlementRow> entitlementsForPlan(long planId) {
		return jdbcClient.sql("SELECT * FROM plan_entitlement WHERE plan_id = :planId ORDER BY capability_id")
				.param("planId", planId)
				.query(PLAN_ENTITLEMENT_ROW_MAPPER)
				.list();
	}
}
