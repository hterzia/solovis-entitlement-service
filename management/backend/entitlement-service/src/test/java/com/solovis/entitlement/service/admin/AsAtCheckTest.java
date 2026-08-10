package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanEntitlementEditRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.AsAtCheckService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * c22–c28 — a past date returns the answer that was live then, and the four refusals never return a
 * value.
 *
 * <p>Time moves forward here, one write at a time, exactly as it would in life; each assertion then
 * asks about a day that has already happened. That is the only way to test reconstruction honestly:
 * a fixture built all at once has no past to recover.
 */
@SpringBootTest
class AsAtCheckTest {

	private static final AtomicInteger UNIQUE = new AtomicInteger();
	private static final ZoneId EASTERN = ZoneId.of("America/New_York");
	private static final LocalDate DAY_ONE = LocalDate.of(2027, 2, 1);

	@Autowired AsAtCheckService asAtCheckService;
	@Autowired OverrideAdminService overrideService;
	@Autowired AccountAdminService accountService;
	@Autowired PlanAdminService planService;
	@Autowired CapabilityAdminService capabilityService;
	@Autowired MutableClock clock;

	@TestConfiguration
	static class DriveableClock {
		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(DAY_ONE, EASTERN);
		}
	}

	private String account;
	private String capability;
	private String plan;

	@BeforeEach
	void seed() {
		clock.advanceTo(DAY_ONE);
		int n = UNIQUE.incrementAndGet();
		plan = "past" + n;
		capability = "past" + n + ".reports.monthly";
		account = "acct_past_" + n;

		planService.create(new PlanCreateRequest(plan, "Past " + n, null));
		planService.designateDefault(plan);
		capabilityService.create(new CapabilityCreateRequest(capability, "Monthly reports", null, "QUANTITY",
				new ValueDto("QUANTITY", null, 50L, null, null, null), null, null));
		accountService.create(new AccountCreateRequest(account, null));
	}

	/** A plan edit, through the real two-step preview/apply the admin surface requires. */
	private void applyPlanValue(long amount) {
		var edit = new PlanEntitlementEditRequest(
				java.util.Map.of(capability, new ValueDto("QUANTITY", null, amount, null, null, null)),
				java.util.List.of(), null, null);
		var preview = planService.preview(plan, edit);
		planService.apply(plan, new PlanEntitlementEditRequest(edit.set(), edit.unset(), null, preview.previewToken()));
	}

	private long amountAsAt(LocalDate asOf) {
		return asAtCheckService.check(account, capability, asOf).decision().value().amount();
	}

	/** c22, c24 — a past answer differs from today's, and matches what was true then. */
	@Test
	void aPastAnswerReflectsThePlanValueAsItStoodThen() {
		clock.advanceTo(DAY_ONE.plusDays(10));
		applyPlanValue(500L);

		clock.advanceTo(DAY_ONE.plusDays(20));

		assertThat(amountAsAt(DAY_ONE.plusDays(5)))
				.as("before the plan edit, the capability default was the whole answer")
				.isEqualTo(50L);
		assertThat(amountAsAt(DAY_ONE.plusDays(15)))
				.as("after it, the plan value was")
				.isEqualTo(500L);
	}

	/** c23 — and the overrides in force at that date, not the ones in force now. */
	@Test
	void aPastAnswerReflectsTheOverridesInForceThen() {
		clock.advanceTo(DAY_ONE.plusDays(5));
		overrideService.create(account, new OverrideCreateRequest(capability, "GRANT",
				new ValueDto("QUANTITY", null, 200L, null, null, null), "Q1 pilot",
				null, DAY_ONE.plusDays(9).toString()));

		clock.advanceTo(DAY_ONE.plusDays(20));

		assertThat(amountAsAt(DAY_ONE.plusDays(7))).as("in force then").isEqualTo(200L);
		assertThat(amountAsAt(DAY_ONE.plusDays(9))).as("c4 — the expiry day is inclusive").isEqualTo(200L);
		assertThat(amountAsAt(DAY_ONE.plusDays(10))).as("ended by then").isEqualTo(50L);
		assertThat(amountAsAt(DAY_ONE.plusDays(2))).as("and not yet created then").isEqualTo(50L);
	}

	/** c25 — an override created after the date does not appear in that date's explanation at all. */
	@Test
	void anOverrideCreatedAfterTheDateIsInvisibleToIt() {
		clock.advanceTo(DAY_ONE.plusDays(10));
		overrideService.create(account, new OverrideCreateRequest(capability, "GRANT",
				new ValueDto("QUANTITY", null, 200L, null, null, null), "later", null, null));

		clock.advanceTo(DAY_ONE.plusDays(20));

		var earlier = asAtCheckService.check(account, capability, DAY_ONE.plusDays(5));
		assertThat(earlier.decision().trace().grants())
				.as("nothing created after the date may be named in its explanation")
				.isEmpty();

		var later = asAtCheckService.check(account, capability, DAY_ONE.plusDays(15));
		assertThat(later.decision().trace().grants()).hasSize(1);
	}

	/** c19, c25 — one that existed but was not in force then is named, and marked as such. */
	@Test
	void anOverrideNotInForceThenIsNamedAndMarked() {
		clock.advanceTo(DAY_ONE.plusDays(5));
		overrideService.create(account, new OverrideCreateRequest(capability, "GRANT",
				new ValueDto("QUANTITY", null, 200L, null, null, null), "short pilot",
				null, DAY_ONE.plusDays(6).toString()));

		clock.advanceTo(DAY_ONE.plusDays(20));

		var after = asAtCheckService.check(account, capability, DAY_ONE.plusDays(10));
		assertThat(after.decision().trace().grants()).singleElement().satisfies(candidate -> {
			assertThat(candidate.outcome()).isEqualTo("NOT_IN_FORCE_ENDED");
			assertThat(candidate.expiresOn()).isEqualTo(DAY_ONE.plusDays(6).toString());
			assertThat(candidate.notInForceSince())
					.as("c20 — the explanation alone says when access changed")
					.isEqualTo(DAY_ONE.plusDays(7).toString());
		});
		assertThat(after.decision().value().amount()).isEqualTo(50L);
	}

	/** c27 — a future date is refused rather than guessed at. */
	@Test
	void aFutureDateIsRefused() {
		assertThatThrownBy(() -> asAtCheckService.check(account, capability, LocalDate.now(clock).plusDays(1)))
				.isInstanceOf(EntitlementApiException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.FUTURE_DATE);
	}

	/** c27 — and a date of today returns the current answer. */
	@Test
	void todayReturnsTheCurrentAnswer() {
		clock.advanceTo(DAY_ONE.plusDays(5));
		overrideService.create(account, new OverrideCreateRequest(capability, "GRANT",
				new ValueDto("QUANTITY", null, 200L, null, null, null), "current", null, null));

		assertThat(amountAsAt(LocalDate.now(clock))).isEqualTo(200L);
	}

	/** c26 — before the account existed is said plainly, and is never a denial or today's value. */
	@Test
	void aDateBeforeTheAccountExistedIsRefused() {
		clock.advanceTo(DAY_ONE.plusDays(20));

		assertThatThrownBy(() -> asAtCheckService.check(account, capability, DAY_ONE.minusDays(1)))
				.isInstanceOf(EntitlementApiException.class)
				.extracting("errorCode")
				.isIn(ErrorCode.BEFORE_ACCOUNT_EXISTED, ErrorCode.BEYOND_HISTORY);
	}

	/** An account nobody has heard of stays the v1 error, not a point-in-time one. */
	@Test
	void anUnknownAccountIsStillAnUnknownAccount() {
		assertThatThrownBy(() -> asAtCheckService.check("acct_never", capability, LocalDate.now(clock)))
				.isInstanceOf(UnknownAccountException.class);
	}

	/** c28 — a capability retired since the date resolves normally, and says when it was retired. */
	@Test
	void aCapabilityRetiredSinceTheDateStillAnswers() {
		clock.advanceTo(DAY_ONE.plusDays(10));
		capabilityService.retire(capability);

		clock.advanceTo(DAY_ONE.plusDays(20));

		var answer = asAtCheckService.check(account, capability, DAY_ONE.plusDays(5));
		assertThat(answer.decision().value().amount())
				.as("it was evaluable then, so it is answerable now")
				.isEqualTo(50L);
		assertThat(answer.capabilityRetiredSince()).as("with its retirement stated").isNotNull();
	}
}
