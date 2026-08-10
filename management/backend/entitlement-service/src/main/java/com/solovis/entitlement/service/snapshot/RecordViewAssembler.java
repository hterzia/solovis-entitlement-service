package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.service.store.AccountOverrideRow;
import com.solovis.entitlement.service.store.AccountRow;
import com.solovis.entitlement.service.store.CapabilityRow;
import com.solovis.entitlement.service.store.CapabilityTierRow;
import com.solovis.entitlement.service.store.PlanEntitlementRow;
import com.solovis.entitlement.service.store.PlanRow;
import com.solovis.entitlement.service.store.SnapshotVersionRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place a {@link RecordBackedView} is built. Every entry point loads all of the view's data
 * itself, so the transaction boundary is visible here and nowhere else.
 *
 * <p>{@link #pointView} and {@link #accountView} read through the read pool. {@link
 * #pointViewInWriteTxn} runs the identical queries through the write client, for the one case the
 * read pool cannot serve: a mutation response's explanation has to reflect the write that was just
 * made, and only the write connection sees its own uncommitted rows.
 *
 * <p>Unknown account and unknown or retired capability are not this class's business. It loads what
 * exists and leaves the absence in the view; {@code Resolver.lookUp} keeps raising the three §6.3
 * errors, so the error taxonomy still has one owner.
 */
@Component
public class RecordViewAssembler {

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

	private final JdbcClient readClient;
	private final JdbcClient writeClient;

	public RecordViewAssembler(
			@Qualifier("entitlementReadJdbcClient") JdbcClient readClient,
			@Qualifier("entitlementWriteJdbcClient") JdbcClient writeClient) {
		this.readClient = readClient;
		this.writeClient = writeClient;
	}

	public RecordBackedView pointView(String accountExternalId, String capabilityKey) {
		return assemblePoint(readClient, accountExternalId, capabilityKey);
	}

	/** The same view over the write connection, so it can see writes this transaction has not committed yet. */
	public RecordBackedView pointViewInWriteTxn(String accountExternalId, String capabilityKey) {
		return assemblePoint(writeClient, accountExternalId, capabilityKey);
	}

	public RecordBackedView accountView(String accountExternalId) {
		return assembleAccountSlice(readClient, accountExternalId);
	}

	private RecordBackedView assemblePoint(JdbcClient client, String accountExternalId, String capabilityKey) {
		long version = queryLatestVersion(client);
		var accountRow = queryAccount(client, accountExternalId);
		if (accountRow.isEmpty()) {
			// No active account, so there is nothing for the other three lookups to key off. The view
			// still answers snapshotVersion(); Resolver.lookUp raises entitlement/unknown-account.
			return new RecordBackedView(RecordBackedView.Mode.POINT, version, accountExternalId, null,
					Map.of(), Map.of(), Map.of());
		}

		var account = accountRow.get();
		String planKey = queryPlanKeyById(client, account.planId()).orElseThrow(() -> new IllegalStateException(
				"Account '" + accountExternalId + "' references plan id " + account.planId() + ", which does not exist."));
		var assignment = new AccountAssignment(accountExternalId, planKey);

		var capabilityRow = queryCapabilityByKey(client, capabilityKey);
		if (capabilityRow.isEmpty()) {
			// Resolver.lookUp raises entitlement/unknown-capability from the empty capability map.
			return new RecordBackedView(RecordBackedView.Mode.POINT, version, accountExternalId, assignment,
					Map.of(), Map.of(), Map.of());
		}

		var capability = RowMappers.toCapability(capabilityRow.get(), queryTiers(client, capabilityRow.get().id()));
		var capabilities = Map.of(capability.key(), capability);

		Map<CapabilityKey, PlanEntitlement> entitlements = queryPlanEntitlement(client, account.planId(), capabilityRow.get().id())
				.map(row -> Map.of(capability.key(), RowMappers.toPlanEntitlement(row, planKey, capability)))
				.orElseGet(Map::of);

		var overrideRows = queryLiveOverrides(client, account.id(), capabilityRow.get().id());
		Map<CapabilityKey, List<AccountOverride>> overrides = overrideRows.isEmpty()
				? Map.of()
				: Map.of(capability.key(), overrideRows.stream()
						.map(row -> RowMappers.toOverride(row, accountExternalId, capability))
						.toList());

		return new RecordBackedView(RecordBackedView.Mode.POINT, version, accountExternalId, assignment,
				capabilities, entitlements, overrides);
	}

	private RecordBackedView assembleAccountSlice(JdbcClient client, String accountExternalId) {
		long version = queryLatestVersion(client);
		var accountRow = queryAccount(client, accountExternalId);
		if (accountRow.isEmpty()) {
			return new RecordBackedView(RecordBackedView.Mode.ACCOUNT_SLICE, version, accountExternalId, null,
					Map.of(), Map.of(), Map.of());
		}

		var account = accountRow.get();
		String planKey = queryPlanKeyById(client, account.planId()).orElseThrow(() -> new IllegalStateException(
				"Account '" + accountExternalId + "' references plan id " + account.planId() + ", which does not exist."));
		var assignment = new AccountAssignment(accountExternalId, planKey);

		// Every capability, retired included — capability() must find a retired one so Resolver can
		// tell "retired" from "unknown"; activeCapabilities() filters, exactly as Snapshot does.
		var tiersByCapabilityId = queryAllTiers(client);
		Map<Long, Capability> capabilitiesById = new LinkedHashMap<>();
		Map<CapabilityKey, Capability> capabilities = new LinkedHashMap<>();
		for (var row : queryAllCapabilities(client)) {
			var capability = RowMappers.toCapability(row, tiersByCapabilityId.getOrDefault(row.id(), List.of()));
			capabilitiesById.put(row.id(), capability);
			capabilities.put(capability.key(), capability);
		}

		Map<CapabilityKey, PlanEntitlement> entitlements = new LinkedHashMap<>();
		for (var row : queryEntitlementsForPlan(client, account.planId())) {
			var capability = capabilitiesById.get(row.capabilityId());
			entitlements.put(capability.key(), RowMappers.toPlanEntitlement(row, planKey, capability));
		}

		Map<CapabilityKey, List<AccountOverride>> overrides = new LinkedHashMap<>();
		for (var row : queryLiveOverridesForAccount(client, account.id())) {
			var capability = capabilitiesById.get(row.capabilityId());
			overrides.computeIfAbsent(capability.key(), k -> new ArrayList<>())
					.add(RowMappers.toOverride(row, accountExternalId, capability));
		}
		Map<CapabilityKey, List<AccountOverride>> frozenOverrides = new LinkedHashMap<>();
		overrides.forEach((key, list) -> frozenOverrides.put(key, List.copyOf(list)));

		return new RecordBackedView(RecordBackedView.Mode.ACCOUNT_SLICE, version, accountExternalId, assignment,
				Map.copyOf(capabilities), Map.copyOf(entitlements), Map.copyOf(frozenOverrides));
	}

	// Query helpers. The SQL matches DecisionReadDao's text; it is repeated here rather than
	// delegated so that all three entry points can run it against whichever JdbcClient they were
	// given, and so DecisionReadDao stays the only class bound to the read pool.

	private long queryLatestVersion(JdbcClient client) {
		return client.sql("SELECT * FROM snapshot_version ORDER BY version DESC LIMIT 1")
				.query(SNAPSHOT_VERSION_ROW_MAPPER)
				.optional()
				.map(SnapshotVersionRow::version)
				.orElse(0L);
	}

	private Optional<AccountRow> queryAccount(JdbcClient client, String externalId) {
		return client.sql("SELECT * FROM account WHERE external_id = :externalId AND status = 'ACTIVE'")
				.param("externalId", externalId)
				.query(ACCOUNT_ROW_MAPPER)
				.optional();
	}

	private Optional<String> queryPlanKeyById(JdbcClient client, long planId) {
		return client.sql("SELECT * FROM plan WHERE id = :id")
				.param("id", planId)
				.query(PLAN_ROW_MAPPER)
				.optional()
				.map(PlanRow::key);
	}

	private Optional<CapabilityRow> queryCapabilityByKey(JdbcClient client, String key) {
		return client.sql("SELECT * FROM capability WHERE key = :key")
				.param("key", key)
				.query(CAPABILITY_ROW_MAPPER)
				.optional();
	}

	private List<CapabilityRow> queryAllCapabilities(JdbcClient client) {
		return client.sql("SELECT * FROM capability ORDER BY area, key")
				.query(CAPABILITY_ROW_MAPPER)
				.list();
	}

	private List<CapabilityTierRow> queryTiers(JdbcClient client, long capabilityId) {
		return client.sql("SELECT * FROM capability_tier WHERE capability_id = :capabilityId ORDER BY ordinal")
				.param("capabilityId", capabilityId)
				.query(TIER_ROW_MAPPER)
				.list();
	}

	private Map<Long, List<CapabilityTierRow>> queryAllTiers(JdbcClient client) {
		Map<Long, List<CapabilityTierRow>> grouped = new HashMap<>();
		for (var row : client.sql("SELECT * FROM capability_tier ORDER BY capability_id, ordinal")
				.query(TIER_ROW_MAPPER)
				.list()) {
			grouped.computeIfAbsent(row.capabilityId(), k -> new ArrayList<>()).add(row);
		}
		return grouped;
	}

	private Optional<PlanEntitlementRow> queryPlanEntitlement(JdbcClient client, long planId, long capabilityId) {
		return client.sql("""
				SELECT * FROM plan_entitlement WHERE plan_id = :planId AND capability_id = :capabilityId
				""")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.query(PLAN_ENTITLEMENT_ROW_MAPPER)
				.optional();
	}

	private List<PlanEntitlementRow> queryEntitlementsForPlan(JdbcClient client, long planId) {
		return client.sql("SELECT * FROM plan_entitlement WHERE plan_id = :planId ORDER BY capability_id")
				.param("planId", planId)
				.query(PLAN_ENTITLEMENT_ROW_MAPPER)
				.list();
	}

	private List<AccountOverrideRow> queryLiveOverrides(JdbcClient client, long accountId, long capabilityId) {
		return client.sql("""
				SELECT * FROM account_override
				WHERE account_id = :accountId AND capability_id = :capabilityId AND removed_at IS NULL
				ORDER BY id
				""")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.query(ACCOUNT_OVERRIDE_ROW_MAPPER)
				.list();
	}

	private List<AccountOverrideRow> queryLiveOverridesForAccount(JdbcClient client, long accountId) {
		return client.sql("""
				SELECT * FROM account_override WHERE account_id = :accountId AND removed_at IS NULL
				ORDER BY capability_id, id
				""")
				.param("accountId", accountId)
				.query(ACCOUNT_OVERRIDE_ROW_MAPPER)
				.list();
	}
}
