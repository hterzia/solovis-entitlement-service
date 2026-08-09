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
public class AuditEventRepository {

	private static final RowMapper<AuditEventRow> ROW_MAPPER = (rs, rowNum) -> new AuditEventRow(
			rs.getLong("seq"),
			rs.getString("occurred_at"),
			rs.getString("actor_kind"),
			rs.getString("actor_id"),
			rs.getString("source"),
			rs.getString("entity_type"),
			rs.getString("entity_id"),
			rs.getString("action"),
			rs.getObject("account_id") == null ? null : rs.getLong("account_id"),
			rs.getObject("plan_id") == null ? null : rs.getLong("plan_id"),
			rs.getObject("capability_id") == null ? null : rs.getLong("capability_id"),
			rs.getString("before_json"),
			rs.getString("after_json"),
			rs.getString("reason"),
			rs.getObject("affected_account_count") == null ? null : rs.getLong("affected_account_count"));

	private final JdbcClient jdbcClient;

	public AuditEventRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(AuditEventRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO audit_event (
				    occurred_at, actor_kind, actor_id, source, entity_type, entity_id, action,
				    account_id, plan_id, capability_id, before_json, after_json, reason, affected_account_count
				) VALUES (
				    :occurredAt, :actorKind, :actorId, :source, :entityType, :entityId, :action,
				    :accountId, :planId, :capabilityId, :beforeJson, :afterJson, :reason, :affectedAccountCount
				)
				""")
				.param("occurredAt", row.occurredAt())
				.param("actorKind", row.actorKind())
				.param("actorId", row.actorId())
				.param("source", row.source())
				.param("entityType", row.entityType())
				.param("entityId", row.entityId())
				.param("action", row.action())
				.param("accountId", row.accountId())
				.param("planId", row.planId())
				.param("capabilityId", row.capabilityId())
				.param("beforeJson", row.beforeJson())
				.param("afterJson", row.afterJson())
				.param("reason", row.reason())
				.param("affectedAccountCount", row.affectedAccountCount())
				.update(keyHolder, "seq");
		return keyHolder.getKey().longValue();
	}

	/**
	 * The highest sequence recorded so far, or empty on a database where nothing has happened yet.
	 *
	 * <p>Every {@code snapshot_version} row references an audit event — "the moment it captures"
	 * (data-model.md). A publish that records no business change of its own, such as a
	 * {@code conformance.changed} announcement, references the latest existing moment rather than
	 * manufacturing an audit event for something no person did. §8's history stays a record of
	 * business changes only.
	 */
	public Optional<Long> findMaxSeq() {
		return jdbcClient.sql("SELECT MAX(seq) FROM audit_event")
				.query(Long.class)
				.optional();
	}

	public Optional<AuditEventRow> findBySeq(long seq) {
		return jdbcClient.sql("SELECT * FROM audit_event WHERE seq = :seq")
				.param("seq", seq)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<AuditEventRow> find(AuditEventFilter filter) {
		StringBuilder sql = new StringBuilder("SELECT * FROM audit_event WHERE 1 = 1");
		if (filter.accountId() != null) {
			sql.append(" AND account_id = :accountId");
		}
		if (filter.planId() != null) {
			sql.append(" AND plan_id = :planId");
		}
		if (filter.capabilityId() != null) {
			sql.append(" AND capability_id = :capabilityId");
		}
		if (filter.actorId() != null) {
			sql.append(" AND actor_id = :actorId");
		}
		if (filter.entityType() != null) {
			sql.append(" AND entity_type = :entityType");
		}
		if (filter.occurredFrom() != null) {
			sql.append(" AND occurred_at >= :occurredFrom");
		}
		if (filter.occurredTo() != null) {
			sql.append(" AND occurred_at < :occurredTo");
		}
		if (filter.beforeSeq() != null) {
			sql.append(" AND seq < :beforeSeq");
		}
		sql.append(" ORDER BY seq DESC LIMIT :limit");

		var spec = jdbcClient.sql(sql.toString()).param("limit", filter.limit());
		if (filter.accountId() != null) {
			spec = spec.param("accountId", filter.accountId());
		}
		if (filter.planId() != null) {
			spec = spec.param("planId", filter.planId());
		}
		if (filter.capabilityId() != null) {
			spec = spec.param("capabilityId", filter.capabilityId());
		}
		if (filter.actorId() != null) {
			spec = spec.param("actorId", filter.actorId());
		}
		if (filter.entityType() != null) {
			spec = spec.param("entityType", filter.entityType());
		}
		if (filter.occurredFrom() != null) {
			spec = spec.param("occurredFrom", filter.occurredFrom());
		}
		if (filter.occurredTo() != null) {
			spec = spec.param("occurredTo", filter.occurredTo());
		}
		if (filter.beforeSeq() != null) {
			spec = spec.param("beforeSeq", filter.beforeSeq());
		}
		return spec.query(ROW_MAPPER).list();
	}
}
