package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AccountRepositoryTest {

	@Autowired
	AccountRepository repository;

	@Autowired
	PlanRepository planRepository;

	long planId;
	long otherPlanId;

	@BeforeEach
	void seedPlans() {
		planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		otherPlanId = planRepository.insert(new PlanRow(null, "enterprise", "Enterprise", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
	}

	private AccountRow account(String externalId, long planId) {
		return new AccountRow(null, externalId, "Northwind Capital", planId,
				"2026-08-09T00:00:00.000Z", "SYSTEM", "billing-sync", "ACTIVE",
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");
	}

	@Test
	void insertAndFindByExternalIdRoundTrip() {
		long id = repository.insert(account("acct_9931", planId));
		AccountRow saved = repository.findByExternalId("acct_9931").orElseThrow();
		assertThat(saved.id()).isEqualTo(id);
		assertThat(saved.planId()).isEqualTo(planId);
	}

	@Test
	void existsByExternalIdReflectsInsert() {
		assertThat(repository.existsByExternalId("acct_1")).isFalse();
		repository.insert(account("acct_1", planId));
		assertThat(repository.existsByExternalId("acct_1")).isTrue();
	}

	@Test
	void searchFiltersByPlanAndQueryAndPagesByCursor() {
		repository.insert(account("acct_a", planId));
		repository.insert(account("acct_b", planId));
		repository.insert(account("acct_c", otherPlanId));

		assertThat(repository.search(null, planId, 0, 10)).extracting(AccountRow::externalId)
				.containsExactly("acct_a", "acct_b");
		assertThat(repository.search("acct_c", null, 0, 10)).extracting(AccountRow::externalId)
				.containsExactly("acct_c");

		var firstPage = repository.search(null, null, 0, 2);
		assertThat(firstPage).hasSize(2);
		long cursor = firstPage.get(firstPage.size() - 1).id();
		var secondPage = repository.search(null, null, cursor, 2);
		assertThat(secondPage).hasSize(1);
	}

	@Test
	void updatePlanAssignmentMovesTheAccountAndRecordsTheSource() {
		long id = repository.insert(account("acct_9931", planId));

		int rows = repository.updatePlanAssignment(id, otherPlanId, "2026-08-09T05:00:00.000Z",
				"PERSON", "a.reyes", "2026-08-09T05:00:00.000Z");

		assertThat(rows).isEqualTo(1);
		AccountRow saved = repository.findById(id).orElseThrow();
		assertThat(saved.planId()).isEqualTo(otherPlanId);
		assertThat(saved.planAssignmentSource()).isEqualTo("PERSON");
		assertThat(saved.planAssignmentActor()).isEqualTo("a.reyes");
	}

	@Test
	void findAllActiveReturnsOnlyActiveAccounts() {
		repository.insert(account("acct_active", planId));
		repository.insert(new AccountRow(null, "acct_closed", "Northwind Capital", planId,
				"2026-08-09T00:00:00.000Z", "SYSTEM", "billing-sync", "CLOSED",
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

		var all = repository.findAllActive();

		assertThat(all).extracting(AccountRow::externalId).contains("acct_active").doesNotContain("acct_closed");
	}
}
