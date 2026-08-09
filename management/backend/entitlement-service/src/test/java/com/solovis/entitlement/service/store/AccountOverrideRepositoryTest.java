package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AccountOverrideRepositoryTest {

	@Autowired
	AccountOverrideRepository repository;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	PlanRepository planRepository;

	@Autowired
	CapabilityRepository capabilityRepository;

	long accountId;
	long capabilityId;

	@BeforeEach
	void seedParents() {
		long planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		accountId = accountRepository.insert(new AccountRow(null, "acct_9931", "Northwind Capital", planId,
				"2026-08-09T00:00:00.000Z", "SYSTEM", "billing-sync", "ACTIVE",
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		capabilityId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", null, "Monthly reports",
				null, "QUANTITY", null, 0L, false, null,
				true, 0L, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
	}

	private AccountOverrideRow grant(long amount, String reason) {
		return new AccountOverrideRow(null, accountId, capabilityId, "GRANT",
				null, amount, false, null, reason,
				"2026-08-09T00:00:00.000Z", "j.okafor", "PERSON",
				null, null, null);
	}

	@Test
	void insertRejectsABlankReason() {
		AccountOverrideRow blank = new AccountOverrideRow(null, accountId, capabilityId, "GRANT",
				null, 200L, false, null, "   ",
				"2026-08-09T00:00:00.000Z", "j.okafor", "PERSON", null, null, null);

		assertThatThrownBy(() -> repository.insert(blank))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void liveOverridesAreVisibleUntilRemoved() {
		long id = repository.insert(grant(200L, "Renewal concession"));

		assertThat(repository.findLive(accountId, capabilityId)).extracting(AccountOverrideRow::id)
				.containsExactly(id);
		assertThat(repository.findLiveForAccount(accountId)).hasSize(1);
		assertThat(repository.findLiveForCapability(capabilityId)).hasSize(1);
		assertThat(repository.countLiveForCapability(capabilityId)).isEqualTo(1L);

		boolean removed = repository.remove(id, "2026-08-09T06:00:00.000Z", "j.okafor", "Investigation closed");

		assertThat(removed).isTrue();
		assertThat(repository.findLive(accountId, capabilityId)).isEmpty();
		assertThat(repository.findById(id).orElseThrow().removedAt()).isEqualTo("2026-08-09T06:00:00.000Z");
	}

	@Test
	void removingAnAlreadyRemovedOverrideIsANoOp() {
		long id = repository.insert(grant(200L, "Renewal concession"));
		assertThat(repository.remove(id, "2026-08-09T06:00:00.000Z", "j.okafor", "First removal")).isTrue();
		assertThat(repository.remove(id, "2026-08-09T07:00:00.000Z", "j.okafor", "Second attempt")).isFalse();
	}

	@Test
	void anAccountMayHoldMultipleOverridesOnTheSameCapability() {
		repository.insert(grant(200L, "Renewal concession"));
		repository.insert(grant(120L, "Migration goodwill"));

		assertThat(repository.findLive(accountId, capabilityId)).hasSize(2);
	}

	@Test
	void findAllLiveExcludesRemovedOverrides() {
		long liveId = repository.insert(grant(200L, "Renewal concession"));
		long removedId = repository.insert(grant(120L, "Migration goodwill"));
		repository.remove(removedId, "2026-08-09T00:00:00.000Z", "j.okafor", "closed");

		var live = repository.findAllLive();

		assertThat(live).extracting(AccountOverrideRow::id).contains(liveId).doesNotContain(removedId);
	}
}
