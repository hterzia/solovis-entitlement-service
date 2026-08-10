package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The reads point-in-time is built from: "the latest audit entry of this kind at or before this
 * sequence" (002 §6.3).
 *
 * <p>Everything here keys off a sequence rather than a timestamp, and that is the whole design. A
 * date is resolved to one {@code asAtSeq} first, and every later lookup is bounded by it, so a past
 * answer gets the same one-coherent-moment property {@code snapshotVersion} gives a live one (v1
 * c31) — a change and its audit row can never be read half-applied.
 *
 * <p>On the read pool, like every other read that is not a write's own. Point-in-time is an operator
 * surface and must never be reachable from {@code /v1} (§6.2): a past answer costs several indexed
 * audit reads, and putting that on the product-facing path would undo 001's central decision.
 */
@Component
public class HistoryReadDao {

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
			rs.getObject("affected_account_count") == null ? null : rs.getLong("affected_account_count"),
			rs.getString("window_transition"));

	private final JdbcClient jdbcClient;

	public HistoryReadDao(@Qualifier("entitlementReadJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * The sequence that closes the day being asked about. {@code occurred_at < boundary}, where the
	 * boundary is the start of the day <em>after</em> the date, because §6.1 answers "as it stood at
	 * the end of that day".
	 */
	public Optional<Long> asAtSeq(String exclusiveBoundaryIso) {
		return jdbcClient.sql("SELECT MAX(seq) FROM audit_event WHERE occurred_at < :boundary")
				.param("boundary", exclusiveBoundaryIso)
				.query(Long.class)
				.optional();
	}

	/** The first entry in the whole trail, for telling "before this account" from "beyond the history". */
	public Optional<String> earliestOccurredAt() {
		return jdbcClient.sql("SELECT MIN(occurred_at) FROM audit_event")
				.query(String.class)
				.optional();
	}

	/**
	 * Which plan the account was on. Both entity types carry {@code planKey} in {@code after_json} —
	 * ACCOUNT from its creation, ACCOUNT_PLAN from every reassignment since. No entry at all means
	 * the account did not exist yet (c26).
	 */
	public Optional<AuditEventRow> latestAccountEntry(long accountId, long asAtSeq) {
		return jdbcClient.sql("""
				SELECT * FROM audit_event
				WHERE account_id = :accountId AND seq <= :asAtSeq
				  AND entity_type IN ('ACCOUNT','ACCOUNT_PLAN')
				  AND after_json IS NOT NULL
				ORDER BY seq DESC LIMIT 1
				""")
				.param("accountId", accountId)
				.param("asAtSeq", asAtSeq)
				.query(ROW_MAPPER)
				.optional();
	}

	/**
	 * What that plan set for the capability. Absent means the plan was silent, so the capability
	 * default applies — the same distinction v1 already draws, and the reason an unset is recorded
	 * with a null {@code after_json} rather than not recorded at all.
	 */
	public Optional<AuditEventRow> latestPlanEntitlementEntry(long planId, long capabilityId, long asAtSeq) {
		return jdbcClient.sql("""
				SELECT * FROM audit_event
				WHERE plan_id = :planId AND capability_id = :capabilityId AND seq <= :asAtSeq
				  AND entity_type = 'PLAN_ENTITLEMENT'
				ORDER BY seq DESC LIMIT 1
				""")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.param("asAtSeq", asAtSeq)
				.query(ROW_MAPPER)
				.optional();
	}

	/**
	 * The capability as it stood — its default, off-value and tiers. CAPABILITY_TIER is included
	 * because appending a tier also writes the whole descriptor, so it can be the most recent
	 * statement of what the capability was.
	 */
	public Optional<AuditEventRow> latestCapabilityEntry(long capabilityId, long asAtSeq) {
		return jdbcClient.sql("""
				SELECT * FROM audit_event
				WHERE capability_id = :capabilityId AND seq <= :asAtSeq
				  AND entity_type IN ('CAPABILITY','CAPABILITY_TIER')
				  AND after_json IS NOT NULL
				ORDER BY seq DESC LIMIT 1
				""")
				.param("capabilityId", capabilityId)
				.param("asAtSeq", asAtSeq)
				.query(ROW_MAPPER)
				.optional();
	}

	/** When the capability was retired, if it has been by then — c28's {@code capabilityRetiredSince}. */
	public Optional<String> retiredAt(long capabilityId, long asAtSeq) {
		return jdbcClient.sql("""
				SELECT occurred_at FROM audit_event
				WHERE capability_id = :capabilityId AND seq <= :asAtSeq
				  AND entity_type = 'CAPABILITY' AND action = 'RETIRE'
				ORDER BY seq DESC LIMIT 1
				""")
				.param("capabilityId", capabilityId)
				.param("asAtSeq", asAtSeq)
				.query(String.class)
				.optional();
	}

	/** The overrides that existed on this account and capability by {@code createdAtOrBefore}. */
	public java.util.List<AccountOverrideRow> knownOverrides(long accountId, long capabilityId, String createdAtOrBefore) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE account_id = :accountId AND capability_id = :capabilityId AND created_at <= :asOf
				ORDER BY id
				""")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.param("asOf", createdAtOrBefore)
				.query(OVERRIDE_ROW_MAPPER)
				.list();
	}

	private static final RowMapper<AccountOverrideRow> OVERRIDE_ROW_MAPPER = (rs, rowNum) -> new AccountOverrideRow(
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
			rs.getString("removed_reason"),
			rs.getString("starts_on"),
			rs.getString("expires_on"));

	/** The account, by external id, whatever its status — a closed account still has a past. */
	public Optional<AccountRow> accountByExternalId(String externalId) {
		return jdbcClient.sql("SELECT * FROM account WHERE external_id = :externalId")
				.param("externalId", externalId)
				.query(ACCOUNT_ROW_MAPPER)
				.optional();
	}

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

	public Optional<CapabilityRow> capabilityByKey(String key) {
		return jdbcClient.sql("SELECT * FROM capability WHERE key = :key")
				.param("key", key)
				.query(CAPABILITY_ROW_MAPPER)
				.optional();
	}

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

	public Optional<Long> planIdByKey(String key) {
		return jdbcClient.sql("SELECT id FROM plan WHERE key = :key")
				.param("key", key)
				.query(Long.class)
				.optional();
	}
}
