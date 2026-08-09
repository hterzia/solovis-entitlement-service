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
			rs.getString("removed_reason"));

	private final JdbcClient jdbcClient;

	public AccountOverrideRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(AccountOverrideRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO account_override (
				    account_id, capability_id, kind, bool_value, qty_value, qty_unlimited, tier_value,
				    reason, created_at, created_by, created_source
				) VALUES (
				    :accountId, :capabilityId, :kind, :boolValue, :qtyValue, :qtyUnlimited, :tierValue,
				    :reason, :createdAt, :createdBy, :createdSource
				)
				""")
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
