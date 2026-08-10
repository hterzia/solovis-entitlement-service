package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window half of 002 at the storage layer: what round-trips, and — the part worth the most —
 * exactly which day an override stops counting on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AccountOverrideWindowTest {

	private static final String T = "2026-08-09T00:00:00.000Z";

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
		long planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false, T, T));
		accountId = accountRepository.insert(new AccountRow(null, "acct_9931", "Northwind Capital", planId,
				T, "SYSTEM", "billing-sync", "ACTIVE", T, T));
		capabilityId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", null, "Monthly reports",
				null, "QUANTITY", null, 0L, false, null, true, 0L, null, "ACTIVE", null, T, T));
	}

	private long grant(long amount, String startsOn, String expiresOn) {
		return repository.insert(new AccountOverrideRow(null, accountId, capabilityId, "GRANT",
				null, amount, false, null, "Q3 pilot", T, "j.okafor", "PERSON",
				null, null, null, startsOn, expiresOn));
	}

	@Test
	void aWindowRoundTripsAndAnOpenEndedOverrideKeepsBothEndsNull() {
		long windowed = grant(200L, "2026-10-01", "2026-12-31");
		long openEnded = grant(50L, null, null);

		assertThat(repository.findById(windowed)).get()
				.extracting(AccountOverrideRow::startsOn, AccountOverrideRow::expiresOn)
				.containsExactly("2026-10-01", "2026-12-31");
		assertThat(repository.findById(openEnded)).get()
				.extracting(AccountOverrideRow::startsOn, AccountOverrideRow::expiresOn)
				.containsExactly(null, null);
	}

	/** c4 — on the expiry date the override still counts; on the following date it does not. */
	@Test
	void theExpiryDayIsInclusiveAndTheDayAfterIsNot() {
		long id = grant(200L, "2026-10-01", "2026-12-31");

		assertThat(inForceIds(LocalDate.of(2026, 12, 31))).contains(id);
		assertThat(inForceIds(LocalDate.of(2027, 1, 1))).doesNotContain(id);
	}

	/** c2 — an override whose start has not arrived takes no part in any decision. */
	@Test
	void anOverrideDoesNotCountBeforeItsStartDateAndDoesOnIt() {
		long id = grant(200L, "2026-10-01", null);

		assertThat(inForceIds(LocalDate.of(2026, 9, 30))).doesNotContain(id);
		assertThat(inForceIds(LocalDate.of(2026, 10, 1))).contains(id);
	}

	@Test
	void anOpenEndedOverrideIsInForceOnEveryDate() {
		long id = grant(50L, null, null);

		assertThat(inForceIds(LocalDate.of(2020, 1, 1))).contains(id);
		assertThat(inForceIds(LocalDate.of(2099, 1, 1))).contains(id);
	}

	/** c16, c17 — removal ends the effect at once, and the record survives to be read. */
	@Test
	void aRemovedOverrideStopsCountingButIsStillThere() {
		long id = grant(200L, null, null);
		repository.remove(id, T, "j.okafor", "Investigation closed");

		assertThat(inForceIds(LocalDate.of(2026, 8, 9))).doesNotContain(id);
		assertThat(repository.findById(id)).get()
				.extracting(AccountOverrideRow::removedBy).isEqualTo("j.okafor");
	}

	/**
	 * c19, c25 — the explanation must be able to name what is no longer counting, and must not name
	 * what did not exist yet.
	 */
	@Test
	void knownOverridesIncludeEveryStandingButExcludeAnythingCreatedLater() {
		long ended = repository.insert(new AccountOverrideRow(null, accountId, capabilityId, "GRANT",
				null, 200L, false, null, "lapsed pilot", "2026-06-01T00:00:00.000Z", "j.okafor", "PERSON",
				null, null, null, null, "2026-06-30"));
		long createdLater = repository.insert(new AccountOverrideRow(null, accountId, capabilityId, "GRANT",
				null, 90L, false, null, "later", "2026-09-01T00:00:00.000Z", "j.okafor", "PERSON",
				null, null, null, null, null));

		var known = repository.findKnown(accountId, capabilityId, "2026-08-09T00:00:00.000Z")
				.stream().map(AccountOverrideRow::id).toList();

		assertThat(known).contains(ended).doesNotContain(createdLater);
	}

	/** What the midnight roll asks: what begins now, and what ended as this day opened. */
	@Test
	void boundaryQueriesFindBothHalvesOfOneMidnight() {
		long starts = grant(200L, "2026-10-01", null);
		long endsAsOctoberOpens = grant(75L, null, "2026-09-30");

		assertThat(repository.findStartingOn(LocalDate.of(2026, 10, 1)))
				.extracting(AccountOverrideRow::id).containsExactly(starts);
		assertThat(repository.findExpiringAtStartOf(LocalDate.of(2026, 10, 1)))
				.extracting(AccountOverrideRow::id).containsExactly(endsAsOctoberOpens);
	}

	@Test
	void aRemovedOverrideIsNeverReportedAsABoundaryTransition() {
		long id = grant(200L, "2026-10-01", null);
		repository.remove(id, T, "j.okafor", "cancelled before it began");

		assertThat(repository.findStartingOn(LocalDate.of(2026, 10, 1))).isEmpty();
	}

	private java.util.List<Long> inForceIds(LocalDate asOf) {
		return repository.findInForce(accountId, capabilityId, asOf)
				.stream().map(AccountOverrideRow::id).toList();
	}
}
