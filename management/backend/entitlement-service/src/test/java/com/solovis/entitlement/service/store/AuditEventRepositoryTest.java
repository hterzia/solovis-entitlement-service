package com.solovis.entitlement.service.store;

import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanPatchRequest;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AuditEventRepositoryTest {

	@Autowired
	AuditEventRepository repository;

	@Autowired
	PlanRepository planRepository;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	PlanAdminService planAdminService;

	private AuditEventRow event(String actorId, Long accountId, String occurredAt) {
		return new AuditEventRow(null, occurredAt, "PERSON", actorId, "UI",
				"PLAN_ENTITLEMENT", "pro", "UPDATE",
				accountId, null, null, null, null, null, null);
	}

	@Test
	void insertReturnsAMonotonicSeqAndFindBySeqRoundTrips() {
		long first = repository.insert(event("a.reyes", null, "2026-08-09T00:00:00.000Z"));
		long second = repository.insert(event("a.reyes", null, "2026-08-09T00:01:00.000Z"));

		assertThat(second).isGreaterThan(first);
		assertThat(repository.findBySeq(first).orElseThrow().actorId()).isEqualTo("a.reyes");
	}

	@Test
	void findFiltersByActorAndPagesDescendingBySeq() {
		repository.insert(event("s.patel", null, "2026-08-09T00:00:00.000Z"));
		long e2 = repository.insert(event("a.reyes", null, "2026-08-09T00:01:00.000Z"));
		repository.insert(event("s.patel", null, "2026-08-09T00:02:00.000Z"));
		long e4 = repository.insert(event("a.reyes", null, "2026-08-09T00:03:00.000Z"));

		var byActor = repository.find(new AuditEventFilter(null, null, null, "a.reyes", null, null, null, null, 10));
		assertThat(byActor).extracting(AuditEventRow::seq).containsExactly(e4, e2);

		var firstPage = repository.find(new AuditEventFilter(null, null, null, "a.reyes", null, null, null, null, 1));
		assertThat(firstPage).extracting(AuditEventRow::seq).containsExactly(e4);

		var secondPage = repository.find(
				new AuditEventFilter(null, null, null, "a.reyes", null, null, null, firstPage.get(0).seq(), 10));
		assertThat(secondPage).extracting(AuditEventRow::seq).containsExactly(e2);
	}

	@Test
	void findFiltersByAccountAndEntityType() {
		long planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		long acct = accountRepository.insert(new AccountRow(null, "acct_42", "Northwind Capital", planId,
				"2026-08-09T00:00:00.000Z", "SYSTEM", "billing-sync", "ACTIVE",
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		repository.insert(event("billing-bot", acct, "2026-08-09T00:00:00.000Z"));
		repository.insert(event("billing-bot", null, "2026-08-09T00:01:00.000Z"));

		var byAccount = repository.find(new AuditEventFilter(acct, null, null, null, null, null, null, null, 10));
		assertThat(byAccount).hasSize(1);

		var byEntityType = repository.find(
				new AuditEventFilter(null, null, null, null, "PLAN_ENTITLEMENT", null, null, null, 10));
		assertThat(byEntityType).hasSize(2);
	}

	@Test
	void findFiltersByPlanId() {
		planAdminService.create(new PlanCreateRequest("t29a.plan", "T29a Plan", null));
		planAdminService.create(new PlanCreateRequest("t29b.plan", "T29b Plan", null));

		// Produces a second PLAN/UPDATE audit event scoped to t29a.plan's planId, in addition
		// to the PLAN/CREATE event from create() above.
		planAdminService.patch("t29a.plan", new PlanPatchRequest("T29a Plan Renamed", null));

		long t29aPlanId = planRepository.findByKey("t29a.plan").orElseThrow().id();
		long t29bPlanId = planRepository.findByKey("t29b.plan").orElseThrow().id();

		var byPlan = repository.find(new AuditEventFilter(null, t29aPlanId, null, null, null, null, null, 50));

		assertThat(byPlan).isNotEmpty();
		assertThat(byPlan).allMatch(row -> row.planId() != null && row.planId() == t29aPlanId);
		assertThat(byPlan).noneMatch(row -> row.planId() != null && row.planId() == t29bPlanId);
		assertThat(byPlan).anyMatch(row -> row.entityType().equals("PLAN") && row.action().equals("UPDATE")
				&& row.entityId().equals("t29a.plan"));
	}

	@Test
	void findFiltersByOccurredAtWindowInclusiveFromExclusiveTo() {
		// occurredAt is a literal we control directly (this repository never derives it from
		// a Clock), so the from/to bounds below are exact - no millisecond-collision risk to
		// guard against.
		long e1 = repository.insert(event("t.window", null, "2026-08-09T00:00:00.000Z"));
		long e2 = repository.insert(event("t.window", null, "2026-08-09T00:01:00.000Z"));
		long e3 = repository.insert(event("t.window", null, "2026-08-09T00:02:00.000Z"));

		var windowed = repository.find(new AuditEventFilter(null, null, "t.window", null,
				"2026-08-09T00:01:00.000Z", "2026-08-09T00:02:00.000Z", null, 10));

		// e2's occurredAt equals the from bound (inclusive) and e3's equals the to bound
		// (exclusive), so only e2 should come back; e1 is before the from bound.
		assertThat(windowed).extracting(AuditEventRow::seq).containsExactly(e2).doesNotContain(e1, e3);
	}
}
