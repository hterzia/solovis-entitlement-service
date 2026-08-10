package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanEntitlementEditRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.AsAtCheckService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.time.MutableClock;
import com.solovis.entitlement.service.window.WindowBoundaryRoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The history-replay property: apply a random sequence of writes with the clock advancing, record
 * what was live on each day as it happens, then ask <em>as at</em> each of those days and require
 * the same answer.
 *
 * <p>This is the strongest available statement that reconstruction and reality agree, and it is
 * unusually strong here because the live and historical paths are the same code — {@code
 * RecordBackedView} either way, {@code Resolver.explain()} either way, only the {@code asOf}
 * differs. It compares two invocations of one implementation rather than a reconstruction checked
 * against a separate original, so a bug would have to be in the choice of moment, which is the only
 * thing 002 actually added.
 *
 * <p>Seeded rather than free-running: a property that fails once a fortnight on a different seed is
 * not a test anybody can act on. The seeds are fixed and the failure message names the day.
 */
@SpringBootTest
class HistoryReplayPropertyTest {

	private static final AtomicInteger UNIQUE = new AtomicInteger();
	private static final ZoneId EASTERN = ZoneId.of("America/New_York");
	private static final LocalDate DAY_ONE = LocalDate.of(2027, 4, 1);
	private static final int DAYS = 40;

	@Autowired AsAtCheckService asAtCheckService;
	@Autowired OverrideAdminService overrideService;
	@Autowired AccountAdminService accountService;
	@Autowired PlanAdminService planService;
	@Autowired CapabilityAdminService capabilityService;
	@Autowired WindowBoundaryRoller roller;
	@Autowired MutableClock clock;

	@TestConfiguration
	static class DriveableClock {
		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(DAY_ONE, EASTERN);
		}
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(longs = {1L, 20260810L, 99L})
	void askingAsAtEachDayReproducesTheAnswerThatWasLiveThatDay(long seed) {
		var random = new Random(seed);
		int n = UNIQUE.incrementAndGet();
		String plan = "replay" + n;
		String capability = "replay" + n + ".reports.monthly";
		String account = "acct_replay_" + n;

		clock.advanceTo(DAY_ONE);
		planService.create(new PlanCreateRequest(plan, "Replay " + n, null));
		planService.designateDefault(plan);
		capabilityService.create(new CapabilityCreateRequest(capability, "Monthly reports", null, "QUANTITY",
				new ValueDto("QUANTITY", null, 50L, null, null, null), null, null));
		accountService.create(new AccountCreateRequest(account, null));

		Map<LocalDate, Long> liveAtTheTime = new LinkedHashMap<>();
		List<String> liveOverrides = new ArrayList<>();

		for (int day = 1; day < DAYS; day++) {
			LocalDate date = DAY_ONE.plusDays(day);
			clock.advanceTo(date);
			// Boundaries first, exactly as the scheduled roll would open the day.
			roller.roll();

			switch (random.nextInt(6)) {
				case 0 -> applyPlanValue(plan, capability, 100L + random.nextInt(400));
				case 1 -> liveOverrides.add(overrideService.create(account, new OverrideCreateRequest(capability,
						"GRANT", new ValueDto("QUANTITY", null, 200L + random.nextInt(600), null, null, null),
						"replay grant", null, windowEnd(random, date))).overrideId());
				case 2 -> liveOverrides.add(overrideService.create(account, new OverrideCreateRequest(capability,
						"HOLD", new ValueDto("QUANTITY", null, (long) random.nextInt(150), null, null, null),
						"replay hold", null, windowEnd(random, date))).overrideId());
				case 3 -> {
					if (!liveOverrides.isEmpty()) {
						String ref = liveOverrides.remove(random.nextInt(liveOverrides.size()));
						overrideService.delete(account, ref, "replay removal");
					}
				}
				default -> { /* a quiet day, which is most of them */ }
			}

			// What was actually live at the end of this day, asked the ordinary way.
			liveAtTheTime.put(date, currentAmount(account, capability));
		}

		clock.advanceTo(DAY_ONE.plusDays(DAYS + 5));

		// Guard against a vacuous pass. A generated history that never moved the answer would make
		// every assertion below trivially true, and the property would look green while testing
		// nothing at all.
		assertThat(liveAtTheTime.values().stream().distinct().count())
				.as("the generated history must actually change the answer, or this proves nothing (seed %d)", seed)
				.isGreaterThan(2);

		liveAtTheTime.forEach((date, wasLive) ->
				assertThat(asAtCheckService.check(account, capability, date).decision().value().amount())
						.as("asking as at %s must reproduce the answer that was live on %s (seed %d)", date, date, seed)
						.isEqualTo(wasLive));
	}

	/** Half the overrides get a window, so the replay exercises boundaries rather than only removals. */
	private String windowEnd(Random random, LocalDate from) {
		return random.nextBoolean() ? null : from.plusDays(1 + random.nextInt(6)).toString();
	}

	private long currentAmount(String account, String capability) {
		return accountService.get(account).entitlements().stream()
				.filter(e -> e.capability().equals(capability))
				.findFirst().orElseThrow()
				.value().amount();
	}

	private void applyPlanValue(String plan, String capability, long amount) {
		var edit = new PlanEntitlementEditRequest(
				Map.of(capability, new ValueDto("QUANTITY", null, amount, null, null, null)),
				List.of(), null, null);
		var preview = planService.preview(plan, edit);
		planService.apply(plan, new PlanEntitlementEditRequest(edit.set(), edit.unset(), null, preview.previewToken()));
	}
}
