package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Not @Transactional: DecisionReadDao queries through the separate read connection pool, which
// only sees data that has actually committed on the write side (c.f. ReadTransactionIsolationTest).
// An @Transactional-wrapped seed row on the write manager would never commit, so the read pool
// would never see it. Every seeded row therefore carries a unique per-test suffix, since nothing
// rolls the DB back between test methods in this shared per-run SQLite file.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DecisionReadDaoTest {

	@Autowired
	DecisionReadDao dao;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	PlanRepository planRepository;

	@Autowired
	CapabilityRepository capabilityRepository;

	@Autowired
	PlanEntitlementRepository planEntitlementRepository;

	@Autowired
	AccountOverrideRepository accountOverrideRepository;

	@Autowired
	SnapshotVersionRepository snapshotVersionRepository;

	@Autowired
	AuditEventRepository auditEventRepository;

	private static String unique() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private long seedAuditEvent() {
		return auditEventRepository.insert(new AuditEventRow(null, "2026-08-10T00:00:00.000Z", "SYSTEM",
				"dao-test", "API", "CAPABILITY", "decision-read-dao-test", "CREATE",
				null, null, null, null, null, null, null));
	}

	private long seedPlan(String key) {
		return planRepository.insert(new PlanRow(null, key, "Plan " + key, null, "ACTIVE", false,
				"2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
	}

	private long seedCapability(String key, String status) {
		long id = capabilityRepository.insert(new CapabilityRow(null, key, key.substring(0, key.indexOf('.')),
				"Display " + key, null, "SWITCH", false, null, false, null, false, null, null,
				"ACTIVE", null, "2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
		if ("RETIRED".equals(status)) {
			capabilityRepository.retire(id, "2026-08-10T01:00:00.000Z", "2026-08-10T01:00:00.000Z");
		}
		return id;
	}

	private long seedAccount(String externalId, long planId, String status) {
		return accountRepository.insert(new AccountRow(null, externalId, "Account " + externalId, planId,
				"2026-08-10T00:00:00.000Z", "SYSTEM", "dao-test", status,
				"2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
	}

	@Test
	void latestVersionReturnsTheHighestCommittedVersion() {
		long v1 = snapshotVersionRepository.insert(new SnapshotVersionRow(null, "2026-08-10T00:00:00.000Z",
				seedAuditEvent(), "{}"));
		long v2 = snapshotVersionRepository.insert(new SnapshotVersionRow(null, "2026-08-10T00:01:00.000Z",
				seedAuditEvent(), "{}"));

		assertThat(dao.latestVersion()).isGreaterThanOrEqualTo(v2).isGreaterThan(v1);
	}

	@Test
	void accountReturnsActiveAndHidesClosed() {
		String suffix = unique();
		long planId = seedPlan("plan_" + suffix);
		String activeExternalId = "acct_active_" + suffix;
		String closedExternalId = "acct_closed_" + suffix;
		seedAccount(activeExternalId, planId, "ACTIVE");
		seedAccount(closedExternalId, planId, "CLOSED");

		assertThat(dao.account(activeExternalId)).isPresent();
		assertThat(dao.account(activeExternalId).orElseThrow().externalId()).isEqualTo(activeExternalId);
		assertThat(dao.account(closedExternalId)).isEmpty();
	}

	@Test
	void planKeyByIdRoundTripsAndIsEmptyForUnknownId() {
		String suffix = unique();
		long planId = seedPlan("plan_" + suffix);

		assertThat(dao.planKeyById(planId)).contains("plan_" + suffix);
		assertThat(dao.planKeyById(-1L)).isEmpty();
	}

	@Test
	void capabilityByKeyAndTiersRoundTrip() {
		String suffix = unique();
		String key = "area.cap_" + suffix;
		long capabilityId = seedCapability(key, "ACTIVE");
		capabilityRepository.insertTier(new CapabilityTierRow(capabilityId, "bronze", 1, "Bronze"));
		capabilityRepository.insertTier(new CapabilityTierRow(capabilityId, "silver", 2, "Silver"));

		CapabilityRow found = dao.capabilityByKey(key).orElseThrow();
		assertThat(found.id()).isEqualTo(capabilityId);

		List<CapabilityTierRow> tiers = dao.tiers(capabilityId);
		assertThat(tiers).extracting(CapabilityTierRow::tierKey).containsExactly("bronze", "silver");
	}

	@Test
	void planEntitlementRoundTripsAndIsEmptyWhenAbsent() {
		String suffix = unique();
		long planId = seedPlan("plan_" + suffix);
		long capabilityId = seedCapability("area.cap_" + suffix, "ACTIVE");
		long otherCapabilityId = seedCapability("area.other_" + suffix, "ACTIVE");
		planEntitlementRepository.upsert(new PlanEntitlementRow(planId, capabilityId, true, null, false, null,
				"2026-08-10T00:00:00.000Z"));

		assertThat(dao.planEntitlement(planId, capabilityId)).isPresent();
		assertThat(dao.planEntitlement(planId, otherCapabilityId)).isEmpty();
	}

	@Test
	void liveOverridesExcludeRemovedOverrides() {
		String suffix = unique();
		long planId = seedPlan("plan_" + suffix);
		long capabilityId = seedCapability("area.cap_" + suffix, "ACTIVE");
		long accountId = seedAccount("acct_" + suffix, planId, "ACTIVE");

		long liveId = accountOverrideRepository.insert(new AccountOverrideRow(null, accountId, capabilityId,
				"GRANT", true, null, false, null, "grant reason", "2026-08-10T00:00:00.000Z", "operator",
				"PERSON", null, null, null));
		long removedId = accountOverrideRepository.insert(new AccountOverrideRow(null, accountId, capabilityId,
				"HOLD", false, null, false, null, "hold reason", "2026-08-10T00:00:00.000Z", "operator",
				"PERSON", null, null, null));
		accountOverrideRepository.remove(removedId, "2026-08-10T01:00:00.000Z", "operator", "no longer needed");

		assertThat(dao.liveOverrides(accountId, capabilityId)).extracting(AccountOverrideRow::id)
				.containsExactly(liveId);
		assertThat(dao.liveOverridesForAccount(accountId)).extracting(AccountOverrideRow::id)
				.containsExactly(liveId);
	}

	@Test
	void activeCapabilitiesExcludesRetiredAndAllCapabilitiesFiltersByStatus() {
		String suffix = unique();
		String activeKey = "area.active_" + suffix;
		String retiredKey = "area.retired_" + suffix;
		seedCapability(activeKey, "ACTIVE");
		seedCapability(retiredKey, "RETIRED");

		assertThat(dao.activeCapabilities()).extracting(CapabilityRow::key).contains(activeKey)
				.doesNotContain(retiredKey);
		assertThat(dao.allCapabilities(null, "RETIRED", null)).extracting(CapabilityRow::key)
				.contains(retiredKey).doesNotContain(activeKey);
	}

	@Test
	void allTiersGroupsByCapabilityPreservingOrdinalOrder() {
		String suffix = unique();
		long capabilityOneId = seedCapability("area.cap1_" + suffix, "ACTIVE");
		long capabilityTwoId = seedCapability("area.cap2_" + suffix, "ACTIVE");
		capabilityRepository.insertTier(new CapabilityTierRow(capabilityOneId, "bronze", 1, "Bronze"));
		capabilityRepository.insertTier(new CapabilityTierRow(capabilityOneId, "silver", 2, "Silver"));
		capabilityRepository.insertTier(new CapabilityTierRow(capabilityTwoId, "basic", 1, "Basic"));

		Map<Long, List<CapabilityTierRow>> allTiers = dao.allTiers();

		assertThat(allTiers.get(capabilityOneId)).extracting(CapabilityTierRow::tierKey)
				.containsExactly("bronze", "silver");
		assertThat(allTiers.get(capabilityTwoId)).extracting(CapabilityTierRow::tierKey)
				.containsExactly("basic");
	}

	@Test
	void entitlementsForPlanReturnsBothOrderedByCapabilityId() {
		String suffix = unique();
		long planId = seedPlan("plan_" + suffix);
		long capabilityOneId = seedCapability("area.cap1_" + suffix, "ACTIVE");
		long capabilityTwoId = seedCapability("area.cap2_" + suffix, "ACTIVE");
		long lowerId = Math.min(capabilityOneId, capabilityTwoId);
		long higherId = Math.max(capabilityOneId, capabilityTwoId);
		planEntitlementRepository.upsert(new PlanEntitlementRow(planId, capabilityOneId, true, null, false, null,
				"2026-08-10T00:00:00.000Z"));
		planEntitlementRepository.upsert(new PlanEntitlementRow(planId, capabilityTwoId, true, null, false, null,
				"2026-08-10T00:00:00.000Z"));

		assertThat(dao.entitlementsForPlan(planId)).extracting(PlanEntitlementRow::capabilityId)
				.containsExactly(lowerId, higherId);
	}
}
