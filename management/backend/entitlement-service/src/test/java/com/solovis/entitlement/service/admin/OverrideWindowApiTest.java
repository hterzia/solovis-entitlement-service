package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.store.SnapshotVersionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 002 c1–c7 and c18 over the admin surface: windows saved, refused, and reported. */
@SpringBootTest
class OverrideWindowApiTest {

	private static final AtomicInteger UNIQUE = new AtomicInteger();

	@Autowired OverrideAdminService overrideService;
	@Autowired AccountAdminService accountService;
	@Autowired PlanAdminService planService;
	@Autowired CapabilityAdminService capabilityService;
	@Autowired SnapshotVersionRepository snapshotVersionRepository;
	@Autowired Clock clock;

	/**
	 * The latest published version — what a replica would next be handed. The service holds no
	 * snapshot of its own, so "has anything reached replicas" is a question about the
	 * {@code snapshot_version} table and nothing else.
	 */
	private long publishedVersion() {
		return snapshotVersionRepository.findLatest().map(SnapshotVersionRow::version).orElse(0L);
	}

	private String account;
	private String capability;

	@BeforeEach
	void seed() {
		int n = UNIQUE.incrementAndGet();
		String plan = "win" + n;
		capability = "win" + n + ".reports.monthly";
		account = "acct_win_" + n;

		planService.create(new PlanCreateRequest(plan, "Windowed " + n, null));
		planService.designateDefault(plan);
		capabilityService.create(new CapabilityCreateRequest(capability, "Monthly reports", null, "QUANTITY",
				new ValueDto("QUANTITY", null, 50L, null, null, null), null, null));
		accountService.create(new AccountCreateRequest(account, null));
	}

	private OverrideCreateRequest grant(long amount, String startsOn, String expiresOn) {
		return new OverrideCreateRequest(capability, "GRANT",
				new ValueDto("QUANTITY", null, amount, null, null, null), "Q3 pilot", startsOn, expiresOn);
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	private AccountDetailDto.OverrideRow onlyOverride() {
		var overrides = accountService.get(account).overrides();
		assertThat(overrides).hasSize(1);
		return overrides.get(0);
	}

	@Test
	void anOverrideWithNoWindowStillBehavesExactlyAsItDidBefore() {
		overrideService.create(account, grant(200L, null, null));

		var row = onlyOverride();
		assertThat(row.startsOn()).isNull();
		assertThat(row.expiresOn()).isNull();
		assertThat(row.standing()).isEqualTo("IN_FORCE");
		assertThat(row.effectNow()).isEqualTo("WINNING");
	}

	@Test
	void aWindowIsSavedAndReportedBackOnTheAccountView() {
		String starts = today().toString();
		String expires = today().plusDays(90).toString();

		overrideService.create(account, grant(200L, starts, expires));

		var row = onlyOverride();
		assertThat(row.startsOn()).isEqualTo(starts);
		assertThat(row.expiresOn()).isEqualTo(expires);
		assertThat(row.standing()).isEqualTo("IN_FORCE");
	}

	/**
	 * c2 — a future-dated grant is visible but takes no part. It must also stay off the replication
	 * feed until it begins: publishing it early would hand a product a promise before its date.
	 */
	@Test
	void aPendingOverrideDoesNotRaiseTheValueAndDoesNotReachReplicas() {
		long versionBefore = publishedVersion();

		var created = overrideService.create(account, grant(200L, today().plusMonths(2).toString(), null));

		assertThat(created.decision().value().amount())
				.as("the plan value stands until the grant begins")
				.isEqualTo(50L);
		assertThat(publishedVersion())
				.as("nothing a replica can see has changed yet")
				.isEqualTo(versionBefore);

		var row = onlyOverride();
		assertThat(row.standing()).isEqualTo("PENDING");
		assertThat(row.effectNow())
				.as("effectNow answers what it is doing to the result, and it is doing nothing")
				.isNull();
	}

	@Test
	void anOverrideInForceTodayDoesReachReplicas() {
		long versionBefore = publishedVersion();

		overrideService.create(account, grant(200L, today().toString(), today().plusDays(30).toString()));

		assertThat(publishedVersion()).isGreaterThan(versionBefore);
	}

	/** The mirror: withdrawing something replicas never received must not emit a removal. */
	@Test
	void removingAPendingOverrideEmitsNothingToReplicas() {
		var created = overrideService.create(account, grant(200L, today().plusMonths(2).toString(), null));
		long versionBefore = publishedVersion();

		overrideService.delete(account, created.overrideId(), "cancelled before it began");

		assertThat(publishedVersion()).isEqualTo(versionBefore);
		assertThat(onlyOverride().standing())
				.as("the record survives its removal, marked as such — c17, and one of c18's four states")
				.isEqualTo("REMOVED");
		assertThat(onlyOverride().effectNow())
				.as("and it is doing nothing to the result")
				.isNull();
	}

	@Test
	void aStartAfterItsExpiryIsRefused() {
		assertThatThrownBy(() -> overrideService.create(account,
				grant(200L, today().plusDays(10).toString(), today().plusDays(3).toString())))
				.isInstanceOf(EntitlementApiException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_WINDOW);
	}

	@Test
	void aBackDatedStartIsRefused() {
		assertThatThrownBy(() -> overrideService.create(account, grant(200L, today().minusDays(1).toString(), null)))
				.isInstanceOf(EntitlementApiException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_WINDOW);
	}

	@Test
	void aWindowWhollyInThePastIsRefused() {
		assertThatThrownBy(() -> overrideService.create(account, grant(200L, null, today().minusDays(1).toString())))
				.isInstanceOf(EntitlementApiException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_WINDOW);
	}

	@Test
	void aMalformedDateIsRefusedAsAWindowProblemRatherThanA500() {
		assertThatThrownBy(() -> overrideService.create(account, grant(200L, "31/12/2026", null)))
				.isInstanceOf(EntitlementApiException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_WINDOW);
	}

	/** c8 — extension is a second overlapping override, never an edit. */
	@Test
	void extendingMeansASecondOverrideAndTheValueDoesNotMoveAtTheHandover() {
		overrideService.create(account, grant(200L, null, today().plusDays(30).toString()));
		var extended = overrideService.create(account, grant(200L, null, today().plusDays(120).toString()));

		assertThat(accountService.get(account).overrides()).hasSize(2);
		assertThat(extended.decision().value().amount())
				.as("both agree while both are in force, so nothing changes at the handover")
				.isEqualTo(200L);
	}
}
