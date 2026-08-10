package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountOverrideRepository {

	private static final RowMapper<AccountOverrideRow> ROW_MAPPER = (rs, rowNum) -> new AccountOverrideRow(
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

	/**
	 * An override takes part in a decision only while it is in force (002 c10): not removed, begun,
	 * and not yet expired. The expiry day is inclusive, which is why the comparison is {@code >=}
	 * rather than {@code >} (c4). Dates are stored as ISO 'YYYY-MM-DD', so lexicographic comparison
	 * is chronological.
	 *
	 * <p>Public because it must have exactly one copy. The service answers from SQLite through three
	 * separate query sites — this repository, {@link DecisionReadDao}, and
	 * {@code snapshot/RecordViewAssembler}, which repeats the DAO's SQL so it can run against either
	 * connection pool. A predicate written out three times is a predicate that will eventually differ
	 * in one of them, and the failure would be silent and one-directional: the service and the feed
	 * would disagree about who has access. Bind {@code :asOf} to an ISO date in the service zone.
	 */
	public static final String IN_FORCE = """
			removed_at IS NULL
			AND (starts_on  IS NULL OR starts_on  <= :asOf)
			AND (expires_on IS NULL OR expires_on >= :asOf)
			""";

	private final JdbcClient jdbcClient;

	public AccountOverrideRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(AccountOverrideRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO account_override (
				    account_id, capability_id, kind, bool_value, qty_value, qty_unlimited, tier_value,
				    reason, created_at, created_by, created_source, starts_on, expires_on
				) VALUES (
				    :accountId, :capabilityId, :kind, :boolValue, :qtyValue, :qtyUnlimited, :tierValue,
				    :reason, :createdAt, :createdBy, :createdSource, :startsOn, :expiresOn
				)
				""")
				.param("startsOn", row.startsOn())
				.param("expiresOn", row.expiresOn())
				.param("accountId", row.accountId())
				.param("capabilityId", row.capabilityId())
				.param("kind", row.kind())
				.param("boolValue", row.boolValue())
				.param("qtyValue", row.qtyValue())
				.param("qtyUnlimited", row.qtyUnlimited() ? 1 : 0)
				.param("tierValue", row.tierValue())
				.param("reason", row.reason())
				.param("createdAt", row.createdAt())
				.param("createdBy", row.createdBy())
				.param("createdSource", row.createdSource())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<AccountOverrideRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM account_override WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<AccountOverrideRow> findLive(long accountId, long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE account_id = :accountId AND capability_id = :capabilityId AND removed_at IS NULL
				ORDER BY id
				""")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.query(ROW_MAPPER)
				.list();
	}

	public List<AccountOverrideRow> findLiveForAccount(long accountId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override WHERE account_id = :accountId AND removed_at IS NULL
				ORDER BY capability_id, id
				""")
				.param("accountId", accountId)
				.query(ROW_MAPPER)
				.list();
	}

	public List<AccountOverrideRow> findLiveForCapability(long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override WHERE capability_id = :capabilityId AND removed_at IS NULL
				ORDER BY account_id, id
				""")
				.param("capabilityId", capabilityId)
				.query(ROW_MAPPER)
				.list();
	}

	public long countLiveForCapability(long capabilityId) {
		return jdbcClient.sql("""
				SELECT COUNT(*) FROM account_override WHERE capability_id = :capabilityId AND removed_at IS NULL
				""")
				.param("capabilityId", capabilityId)
				.query(Long.class)
				.single();
	}

	/** Every LIVE override across all accounts — snapshot assembly only (c.f. findLiveForAccount for a single account). */
	public List<AccountOverrideRow> findAllLive() {
		return jdbcClient.sql("SELECT * FROM account_override WHERE removed_at IS NULL")
				.query(ROW_MAPPER)
				.list();
	}

	/** The overrides deciding one account's value for one capability at {@code asOf} (002 c10). */
	public List<AccountOverrideRow> findInForce(long accountId, long capabilityId, LocalDate asOf) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE account_id = :accountId AND capability_id = :capabilityId AND
				""" + IN_FORCE + " ORDER BY id")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.param("asOf", asOf.toString())
				.query(ROW_MAPPER)
				.list();
	}

	/** Every in-force override for one account — the account view and whole-account resolution. */
	public List<AccountOverrideRow> findInForceForAccount(long accountId, LocalDate asOf) {
		return jdbcClient.sql("""
				SELECT * FROM account_override WHERE account_id = :accountId AND
				""" + IN_FORCE + " ORDER BY capability_id, id")
				.param("accountId", accountId)
				.param("asOf", asOf.toString())
				.query(ROW_MAPPER)
				.list();
	}

	/** Every in-force override across all accounts — snapshot assembly, which publishes only what is in force. */
	public List<AccountOverrideRow> findAllInForce(LocalDate asOf) {
		return jdbcClient.sql("SELECT * FROM account_override WHERE " + IN_FORCE)
				.param("asOf", asOf.toString())
				.query(ROW_MAPPER)
				.list();
	}

	/**
	 * Every override on this account and capability that <em>existed</em> at {@code createdAtOrBefore},
	 * whatever its standing then — in force, not yet begun, ended, or removed (002 c19, c25).
	 *
	 * <p>This is what lets an explanation say "there was a GRANT of 200 and it ended on 30 June"
	 * rather than the true but useless "no GRANT in force". Overrides created after the moment asked
	 * about are excluded, so a past explanation cannot mention something that did not exist yet.
	 */
	public List<AccountOverrideRow> findKnown(long accountId, long capabilityId, String createdAtOrBefore) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE account_id = :accountId AND capability_id = :capabilityId
				  AND created_at <= :asOf
				ORDER BY id
				""")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.param("asOf", createdAtOrBefore)
				.query(ROW_MAPPER)
				.list();
	}

	/** Overrides beginning at the midnight that opens {@code date} — the roll's "what starts now". */
	public List<AccountOverrideRow> findStartingOn(LocalDate date) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE starts_on = :date AND removed_at IS NULL
				  AND (expires_on IS NULL OR expires_on >= :date)
				ORDER BY id
				""")
				.param("date", date.toString())
				.query(ROW_MAPPER)
				.list();
	}

	/**
	 * Overrides ending at the midnight that opens {@code date} — that is, whose inclusive last day
	 * was the day before. Expressed against the opening date so the roll asks one question of both
	 * halves of a boundary.
	 */
	public List<AccountOverrideRow> findExpiringAtStartOf(LocalDate date) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE expires_on = :previousDate AND removed_at IS NULL
				ORDER BY id
				""")
				.param("previousDate", date.minusDays(1).toString())
				.query(ROW_MAPPER)
				.list();
	}

	public boolean remove(long id, String removedAt, String removedBy, String removedReason) {
		int rows = jdbcClient.sql("""
				UPDATE account_override SET removed_at = :removedAt, removed_by = :removedBy, removed_reason = :removedReason
				WHERE id = :id AND removed_at IS NULL
				""")
				.param("id", id)
				.param("removedAt", removedAt)
				.param("removedBy", removedBy)
				.param("removedReason", removedReason)
				.update();
		return rows == 1;
	}
}
