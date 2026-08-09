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
public class AccountRepository {

	private static final RowMapper<AccountRow> ROW_MAPPER = (rs, rowNum) -> new AccountRow(
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

	private final JdbcClient jdbcClient;

	public AccountRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(AccountRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO account (
				    external_id, name, plan_id, plan_assigned_at,
				    plan_assignment_source, plan_assignment_actor, status, created_at, updated_at
				) VALUES (
				    :externalId, :name, :planId, :planAssignedAt,
				    :planAssignmentSource, :planAssignmentActor, :status, :createdAt, :updatedAt
				)
				""")
				.param("externalId", row.externalId())
				.param("name", row.name())
				.param("planId", row.planId())
				.param("planAssignedAt", row.planAssignedAt())
				.param("planAssignmentSource", row.planAssignmentSource())
				.param("planAssignmentActor", row.planAssignmentActor())
				.param("status", row.status())
				.param("createdAt", row.createdAt())
				.param("updatedAt", row.updatedAt())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<AccountRow> findByExternalId(String externalId) {
		return jdbcClient.sql("SELECT * FROM account WHERE external_id = :externalId")
				.param("externalId", externalId)
				.query(ROW_MAPPER)
				.optional();
	}

	public Optional<AccountRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM account WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public boolean existsByExternalId(String externalId) {
		return jdbcClient.sql("SELECT COUNT(*) FROM account WHERE external_id = :externalId")
				.param("externalId", externalId)
				.query(Integer.class)
				.single() > 0;
	}

	public List<AccountRow> search(String q, Long planId, long afterId, int limit) {
		StringBuilder sql = new StringBuilder("SELECT * FROM account WHERE id > :afterId");
		if (planId != null) {
			sql.append(" AND plan_id = :planId");
		}
		if (q != null) {
			sql.append(" AND (external_id LIKE :query ESCAPE '\\' OR name LIKE :query ESCAPE '\\')");
		}
		sql.append(" ORDER BY id LIMIT :limit");

		var spec = jdbcClient.sql(sql.toString())
				.param("afterId", afterId)
				.param("limit", limit);
		if (planId != null) {
			spec = spec.param("planId", planId);
		}
		if (q != null) {
			spec = spec.param("query", SqlLike.contains(q));
		}
		return spec.query(ROW_MAPPER).list();
	}

	/** Every ACTIVE account — used only by SnapshotAssembler at startup and full-resync; no cursor because a full assembly needs all rows regardless. */
	public List<AccountRow> findAllActive() {
		return jdbcClient.sql("SELECT * FROM account WHERE status = 'ACTIVE'")
				.query(ROW_MAPPER)
				.list();
	}

	public int updatePlanAssignment(long accountId, long planId, String assignedAt, String source, String actor,
			String updatedAt) {
		return jdbcClient.sql("""
				UPDATE account SET
				    plan_id = :planId,
				    plan_assigned_at = :assignedAt,
				    plan_assignment_source = :source,
				    plan_assignment_actor = :actor,
				    updated_at = :updatedAt
				WHERE id = :accountId
				""")
				.param("accountId", accountId)
				.param("planId", planId)
				.param("assignedAt", assignedAt)
				.param("source", source)
				.param("actor", actor)
				.param("updatedAt", updatedAt)
				.update();
	}
}
