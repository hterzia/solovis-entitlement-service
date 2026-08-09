package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PlanEntitlementRepository {

	private static final RowMapper<PlanEntitlementRow> ROW_MAPPER = (rs, rowNum) -> new PlanEntitlementRow(
			rs.getLong("plan_id"),
			rs.getLong("capability_id"),
			rs.getObject("bool_value") == null ? null : rs.getBoolean("bool_value"),
			rs.getObject("qty_value") == null ? null : rs.getLong("qty_value"),
			rs.getBoolean("qty_unlimited"),
			rs.getString("tier_value"),
			rs.getString("updated_at"));

	private final JdbcClient jdbcClient;

	public PlanEntitlementRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public void upsert(PlanEntitlementRow row) {
		jdbcClient.sql("""
				INSERT INTO plan_entitlement (plan_id, capability_id, bool_value, qty_value, qty_unlimited, tier_value, updated_at)
				VALUES (:planId, :capabilityId, :boolValue, :qtyValue, :qtyUnlimited, :tierValue, :updatedAt)
				ON CONFLICT (plan_id, capability_id) DO UPDATE SET
				    bool_value = excluded.bool_value,
				    qty_value = excluded.qty_value,
				    qty_unlimited = excluded.qty_unlimited,
				    tier_value = excluded.tier_value,
				    updated_at = excluded.updated_at
				""")
				.param("planId", row.planId())
				.param("capabilityId", row.capabilityId())
				.param("boolValue", row.boolValue())
				.param("qtyValue", row.qtyValue())
				.param("qtyUnlimited", row.qtyUnlimited() ? 1 : 0)
				.param("tierValue", row.tierValue())
				.param("updatedAt", row.updatedAt())
				.update();
	}

	public int delete(long planId, long capabilityId) {
		return jdbcClient.sql("DELETE FROM plan_entitlement WHERE plan_id = :planId AND capability_id = :capabilityId")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.update();
	}

	public List<PlanEntitlementRow> findByPlan(long planId) {
		return jdbcClient.sql("SELECT * FROM plan_entitlement WHERE plan_id = :planId ORDER BY capability_id")
				.param("planId", planId)
				.query(ROW_MAPPER)
				.list();
	}

	public Optional<PlanEntitlementRow> find(long planId, long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM plan_entitlement WHERE plan_id = :planId AND capability_id = :capabilityId
				""")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<Long> findPlanIdsUsingCapability(long capabilityId) {
		return jdbcClient.sql("""
				SELECT DISTINCT plan_id FROM plan_entitlement WHERE capability_id = :capabilityId
				""")
				.param("capabilityId", capabilityId)
				.query(Long.class)
				.list();
	}
}
