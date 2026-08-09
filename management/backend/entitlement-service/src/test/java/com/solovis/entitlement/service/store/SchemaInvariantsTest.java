package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SchemaInvariantsTest {

	@Autowired
	@Qualifier("entitlementWriteJdbcClient")
	JdbcClient jdbcClient;

	@Autowired
	PlanRepository planRepository;

	@Autowired
	CapabilityRepository capabilityRepository;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	AuditEventRepository auditEventRepository;

	@Autowired
	SnapshotVersionRepository snapshotVersionRepository;

	@Test
	void auditEventRejectsUpdateAndDelete() {
		long seq = auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "PERSON",
				"a.reyes", "UI", "PLAN_ENTITLEMENT", "pro", "UPDATE", null, null, null, null, null, null, null));

		assertThatThrownBy(() -> jdbcClient.sql("UPDATE audit_event SET reason = 'tampered' WHERE seq = :seq")
				.param("seq", seq)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM audit_event WHERE seq = :seq")
				.param("seq", seq)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(auditEventRepository.findBySeq(seq)).isPresent();
	}

	@Test
	void onlyOnePlanCanBeTheDefaultAtTheSchemaLevel() {
		long free = planRepository.insert(new PlanRow(null, "free", "Free", null, "ACTIVE", true,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

		assertThatThrownBy(() -> jdbcClient.sql("""
				INSERT INTO plan (key, name, status, is_default_for_new_accounts, created_at, updated_at)
				VALUES ('pro', 'Pro', 'ACTIVE', 1, '2026-08-09T00:00:00.000Z', '2026-08-09T00:00:00.000Z')
				""").update())
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(planRepository.findById(free).orElseThrow().defaultForNewAccounts()).isTrue();
	}

	@Test
	void planEntitlementTierValueMustBeADeclaredTierOfTheSameCapability() {
		long planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		long capabilityId = capabilityRepository.insert(new CapabilityRow(null, "support.level", null,
				"Support level", null, "TIER", null, null, false, "community",
				false, null, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		capabilityRepository.insertTier(new CapabilityTierRow(capabilityId, "community", 0, "Community"));

		assertThatThrownBy(() -> jdbcClient.sql("""
				INSERT INTO plan_entitlement (plan_id, capability_id, tier_value, updated_at)
				VALUES (:planId, :capabilityId, 'platinum', '2026-08-09T00:00:00.000Z')
				""")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void accountOverrideReasonMustBeNonBlankAfterTrimming() {
		long planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		long accountId = accountRepository.insert(new AccountRow(null, "acct_4471", "Northwind Capital", planId,
				"2026-08-09T00:00:00.000Z", "SYSTEM", "billing-sync", "ACTIVE",
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		long capabilityId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", null,
				"Monthly reports", null, "QUANTITY", null, 0L, false, null,
				true, 0L, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

		assertThatThrownBy(() -> jdbcClient.sql("""
				INSERT INTO account_override (account_id, capability_id, kind, bool_value, reason, created_at, created_by, created_source)
				VALUES (:accountId, :capabilityId, 'GRANT', 1, '   ', '2026-08-09T00:00:00.000Z', 'j.okafor', 'PERSON')
				""")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void snapshotVersionMustReferenceARealAuditSeq() {
		assertThatThrownBy(() -> snapshotVersionRepository.insert(
				new SnapshotVersionRow(null, "2026-08-09T00:00:00.000Z", 999_999L, "{}")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
