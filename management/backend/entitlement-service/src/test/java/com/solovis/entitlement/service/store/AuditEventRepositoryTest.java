package com.solovis.entitlement.service.store;

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

		var byActor = repository.find(new AuditEventFilter(null, null, "a.reyes", null, null, null, null, 10));
		assertThat(byActor).extracting(AuditEventRow::seq).containsExactly(e4, e2);

		var firstPage = repository.find(new AuditEventFilter(null, null, "a.reyes", null, null, null, null, 1));
		assertThat(firstPage).extracting(AuditEventRow::seq).containsExactly(e4);

		var secondPage = repository.find(
				new AuditEventFilter(null, null, "a.reyes", null, null, null, firstPage.get(0).seq(), 10));
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

		var byAccount = repository.find(new AuditEventFilter(acct, null, null, null, null, null, null, 10));
		assertThat(byAccount).hasSize(1);

		var byEntityType = repository.find(
				new AuditEventFilter(null, null, null, "PLAN_ENTITLEMENT", null, null, null, 10));
		assertThat(byEntityType).hasSize(2);
	}
}
