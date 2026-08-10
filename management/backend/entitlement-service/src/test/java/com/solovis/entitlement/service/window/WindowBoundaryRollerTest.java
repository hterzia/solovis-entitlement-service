package com.solovis.entitlement.service.window;

import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.snapshot.SnapshotAssembler;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.AuditEventRow;
import com.solovis.entitlement.service.store.ServiceStateRepository;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.store.SnapshotVersionRow;
import com.solovis.entitlement.service.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * c11–c13 and c30 — a start raises the value and an end releases it with nobody acting, both reach
 * replicas, and both are recorded as the passage of time rather than as an operator's act.
 *
 * <p>Driven by moving a {@link MutableClock} across midnight rather than by editing anything, which
 * is what 002's definition of done requires.
 */
@SpringBootTest
class WindowBoundaryRollerTest {

	private static final AtomicInteger UNIQUE = new AtomicInteger();
	private static final ZoneId EASTERN = ZoneId.of("America/New_York");
	/** Arbitrary and fixed. Nothing here depends on "now", and a real date would make the two
	 *  daylight-saving tests behave differently on the two days a year they describe. */
	private static final LocalDate BASE = LocalDate.of(2027, 6, 15);

	@Autowired WindowBoundaryRoller roller;
	@Autowired OverrideAdminService overrideService;
	@Autowired AccountAdminService accountService;
	@Autowired PlanAdminService planService;
	@Autowired CapabilityAdminService capabilityService;
	@Autowired AuditEventRepository auditEventRepository;
	@Autowired ServiceStateRepository serviceStateRepository;
	@Autowired SnapshotVersionRepository snapshotVersionRepository;
	@Autowired SnapshotAssembler snapshotAssembler;

	@Autowired MutableClock clock;

	/**
	 * A driveable clock in place of the wall clock. Declared here rather than globally so that
	 * every other test keeps the real one — this is the only suite that needs time to move, and a
	 * shared mutable clock would make unrelated tests depend on the order they ran in.
	 */
	@TestConfiguration
	static class DriveableClock {
		@Bean
		@Primary
		MutableClock mutableClock() {
			// A fixed, arbitrary starting date. Nothing here depends on "now", and pinning it stops
			// the suite behaving differently on the two days a year the transitions actually fall.
			return new MutableClock(LocalDate.of(2027, 6, 15), EASTERN);
		}
	}

	private String account;
	private String capability;

	/**
	 * The clock and {@code rolledThrough} are shared mutable state on one Spring context, so each
	 * test puts both back to a known day. Without this a test that moved time forward would decide
	 * what the next one saw, and — worse — a test that moves time <em>backwards</em> would find
	 * {@code rolledThrough} already ahead of its "today", making every roll a silent no-op and its
	 * assertions pass on the read-path predicate alone.
	 */
	private void resetTo(LocalDate date) {
		clock.advanceTo(date);
		serviceStateRepository.put(WindowBoundaryRoller.ROLLED_THROUGH, date.toString(),
				"2027-01-01T00:00:00.000Z");
	}

	@BeforeEach
	void seed() {
		resetTo(BASE);
		int n = UNIQUE.incrementAndGet();
		String plan = "roll" + n;
		capability = "roll" + n + ".reports.monthly";
		account = "acct_roll_" + n;

		planService.create(new PlanCreateRequest(plan, "Rolled " + n, null));
		planService.designateDefault(plan);
		capabilityService.create(new CapabilityCreateRequest(capability, "Monthly reports", null, "QUANTITY",
				new ValueDto("QUANTITY", null, 50L, null, null, null), null, null));
		accountService.create(new AccountCreateRequest(account, null));
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	private String createGrant(long amount, String startsOn, String expiresOn) {
		return overrideService.create(account, new OverrideCreateRequest(capability, "GRANT",
				new ValueDto("QUANTITY", null, amount, null, null, null), "Q3 pilot", startsOn, expiresOn))
				.overrideId();
	}

	/**
	 * How many transitions of one kind this override has accumulated. Counted per override rather
	 * than from {@code roll()}'s return, because every test in this class shares one database and a
	 * roll legitimately publishes other tests' boundaries falling on the same date.
	 */
	private long transitionCount(String ref, String action) {
		return transitionsFor(ref, action).size();
	}

	private long publishedVersion() {
		return snapshotVersionRepository.findLatest().map(SnapshotVersionRow::version).orElse(0L);
	}

	private long resolvedAmount() {
		return accountService.get(account).entitlements().stream()
				.filter(e -> e.capability().equals(capability))
				.findFirst().orElseThrow()
				.value().amount();
	}

	private java.util.List<AuditEventRow> transitionsFor(String ref, String action) {
		return auditEventRepository.find(new AuditEventFilter(null, null, null, "clock", "OVERRIDE",
				null, null, null, 500)).stream()
				.filter(row -> row.entityId().equals(ref) && row.action().equals(action))
				.toList();
	}

	/** c11 — a GRANT dated for the future raises the value on its date, with nobody acting. */
	@Test
	void aStartRaisesTheValueWhenTheClockReachesIt() {
		LocalDate start = today().plusDays(1);
		String ref = createGrant(200L, start.toString(), null);
		roller.roll();

		assertThat(resolvedAmount()).as("the plan value stands the day before").isEqualTo(50L);
		assertThat(transitionCount(ref, "BEGIN")).isZero();
		long versionBefore = publishedVersion();

		clock.advanceTo(start);
		roller.roll();

		assertThat(resolvedAmount()).as("and the grant applies the day it begins").isEqualTo(200L);
		assertThat(transitionCount(ref, "BEGIN")).isEqualTo(1);
		assertThat(publishedVersion()).as("replicas are told (c13)").isGreaterThan(versionBefore);
	}

	/** c12 — and an expiry releases it, again with nobody acting. */
	@Test
	void anEndReleasesTheValueWhenTheClockPassesIt() {
		LocalDate lastDay = today().plusDays(1);
		String ref = createGrant(200L, null, lastDay.toString());
		roller.roll();

		clock.advanceTo(lastDay);
		roller.roll();
		assertThat(resolvedAmount()).as("c4 — the expiry day is inclusive").isEqualTo(200L);

		clock.advanceTo(lastDay.plusDays(1));
		roller.roll();

		assertThat(resolvedAmount()).as("and the plan value resumes the day after").isEqualTo(50L);
		assertThat(transitionCount(ref, "END")).isEqualTo(1);
	}

	/** c30 — recorded as made by the passage of time, and as legible as any other entry. */
	@Test
	void aBeginningAndAnEndingAreRecordedAsTheClocksDoing() {
		LocalDate start = today().plusDays(1);
		String ref = createGrant(200L, start.toString(), start.toString());
		roller.roll();

		clock.advanceTo(start);
		roller.roll();
		clock.advanceTo(start.plusDays(1));
		roller.roll();

		assertThat(transitionsFor(ref, "BEGIN")).isNotEmpty().allSatisfy(row -> {
			assertThat(row.source()).isEqualTo("CLOCK");
			assertThat(row.actorKind()).isEqualTo("SYSTEM");
			assertThat(row.actorId()).isEqualTo("clock");
			assertThat(row.windowTransition()).isEqualTo("START");
			assertThat(row.reason()).as("legible without knowing what a window is").contains("Began on");
		});
		assertThat(transitionsFor(ref, "END")).isNotEmpty().allSatisfy(row -> {
			assertThat(row.windowTransition()).isEqualTo("EXPIRY");
			assertThat(row.reason()).contains("Ended after");
		});
	}

	/** The safety interval guarantees a second run; it must find nothing to do. */
	@Test
	void rollingTwiceInAMinutePublishesNothingTheSecondTime() {
		LocalDate start = today().plusDays(1);
		String ref = createGrant(200L, start.toString(), null);
		roller.roll();

		clock.advanceTo(start);
		roller.roll();
		long versionAfterFirst = publishedVersion();

		assertThat(roller.roll()).as("idempotent, because the safety interval guarantees it happens").isZero();
		assertThat(publishedVersion()).isEqualTo(versionAfterFirst);
		assertThat(transitionCount(ref, "BEGIN")).as("and no duplicate row in the record").isEqualTo(1);
	}

	/** Cloud Run replaces instances, so being down across a midnight is a normal path, not an edge case. */
	@Test
	void aMissedMidnightIsCaughtUpRatherThanLost() {
		LocalDate start = today().plusDays(1);
		String ref = createGrant(200L, start.toString(), null);
		roller.roll();

		// Three days pass with the service down — nothing calls roll().
		clock.advanceTo(start.plusDays(3));
		roller.roll();

		assertThat(transitionCount(ref, "BEGIN"))
				.as("the boundary that passed while it was down still lands, exactly once")
				.isEqualTo(1);
		assertThat(resolvedAmount()).isEqualTo(200L);
		assertThat(serviceStateRepository.find(WindowBoundaryRoller.ROLLED_THROUGH))
				.contains(start.plusDays(3).toString());
	}

	/**
	 * The two days a year a day is not 24 hours. The failure this guards against is silent and
	 * twice-yearly, which is the worst combination for finding it late: an override in force across
	 * the transition must be in force for the whole of that day and not a moment less.
	 */
	@Test
	void anOverrideSpanningTheSpringForwardDayIsInForceForAllOfIt() {
		assertInForceAcross(LocalDate.of(2027, 3, 14));
	}

	@Test
	void anOverrideSpanningTheAutumnBackDayIsInForceForAllOfIt() {
		assertInForceAcross(LocalDate.of(2027, 11, 7));
	}

	private void assertInForceAcross(LocalDate transitionDay) {
		// Moves time backwards relative to BASE, so rolledThrough has to come back with it or every
		// roll below is a no-op and the assertions prove only that the SQL predicate works.
		resetTo(transitionDay.minusDays(1));
		createGrant(200L, transitionDay.toString(), transitionDay.toString());
		assertThat(resolvedAmount()).as("not yet, the day before").isEqualTo(50L);

		clock.advanceTo(transitionDay);
		assertThat(roller.roll())
				.as("the beginning is published on a %d-hour day, exactly once", hoursIn(transitionDay))
				.isEqualTo(1);
		assertThat(resolvedAmount())
				.as("and it is in force for the whole of that day")
				.isEqualTo(200L);

		clock.advanceTo(transitionDay.plusDays(1));
		assertThat(roller.roll()).as("and the ending is published the day after, exactly once").isEqualTo(1);
		assertThat(resolvedAmount()).isEqualTo(50L);
	}

	private long hoursIn(LocalDate date) {
		return java.time.Duration.between(
				date.atStartOfDay(EASTERN).toInstant(),
				date.plusDays(1).atStartOfDay(EASTERN).toInstant()).toHours();
	}

	/** A replica is told what counts, never what merely exists (c14). */
	@Test
	void theFullResyncArtifactCarriesOnlyWhatIsInForce() {
		LocalDate start = today().plusDays(1);
		createGrant(200L, start.toString(), null);

		assertThat(snapshotAssembler.assembleFull().allLiveOverrides())
				.as("a pending override is not in the artifact replicas consume")
				.noneMatch(o -> o.accountExternalId().equals(account));

		clock.advanceTo(start);
		roller.roll();

		assertThat(snapshotAssembler.assembleFull().allLiveOverrides())
				.as("and is, once it begins")
				.anyMatch(o -> o.accountExternalId().equals(account));
	}
}
