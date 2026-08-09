package com.solovis.entitlement.service.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceZoneTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

	@Test
	void easternIsAcceptedBecauseItsTransitionsFallAtTwoInTheMorning() {
		assertThatCode(() -> ServiceZone.validated(ZoneId.of("America/New_York"), TODAY))
				.doesNotThrowAnyException();
	}

	@Test
	void aFixedOffsetZoneIsAcceptedBecauseItNeverTransitionsAtAll() {
		assertThatCode(() -> ServiceZone.validated(ZoneId.of("UTC"), TODAY)).doesNotThrowAnyException();
	}

	/**
	 * The daylight-saving days are 23 and 25 hours long, but both start at a well-defined midnight,
	 * which is the only property the whole-day window model needs.
	 */
	@Test
	void easternsDaylightSavingDaysStillHaveExactlyOneMidnight() {
		var rules = ZoneId.of("America/New_York").getRules();

		for (LocalDate day : new LocalDate[] { LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1) }) {
			assertThat(rules.getValidOffsets(day.atStartOfDay()))
					.as("midnight on %s", day)
					.hasSize(1);
		}
	}

	/**
	 * The check must actually reject something, or it is decoration. Rather than hard-coding a zone
	 * whose rules could change between tzdb releases, this finds one that genuinely transitions at
	 * midnight in the current JDK's data and asserts that zone is refused.
	 */
	@Test
	void aZoneThatTransitionsAtMidnightIsRefused() {
		Optional<ZoneId> midnightTransitioning = ZoneId.getAvailableZoneIds().stream()
				.sorted()
				.map(ZoneId::of)
				.filter(zone -> !ServiceZone.datesWithAmbiguousMidnight(
						zone, TODAY.minusYears(ServiceZone.HORIZON_YEARS), TODAY.plusYears(ServiceZone.HORIZON_YEARS))
						.isEmpty())
				.findFirst();

		assertThat(midnightTransitioning)
				.as("the JDK's tzdb should contain at least one zone that transitions at midnight")
				.isPresent();

		ZoneId zone = midnightTransitioning.get();
		assertThatThrownBy(() -> ServiceZone.validated(zone, TODAY))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(zone.getId())
				.hasMessageContaining("midnight");
	}
}
