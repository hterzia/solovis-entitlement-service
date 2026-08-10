package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 002 §3.2 — the four states, and the day each boundary falls on. */
class OverrideStandingTest {

	private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
	private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);

	private static OverrideStanding at(LocalDate startsOn, LocalDate expiresOn, LocalDate removedOn, LocalDate asOf) {
		return OverrideStanding.of(Optional.ofNullable(startsOn), Optional.ofNullable(expiresOn),
				Optional.ofNullable(removedOn), asOf);
	}

	@Test
	void anOverrideWithNoWindowIsInForceOnEveryDate() {
		assertThat(at(null, null, null, LocalDate.of(1999, 1, 1))).isEqualTo(OverrideStanding.IN_FORCE);
		assertThat(at(null, null, null, LocalDate.of(2099, 1, 1))).isEqualTo(OverrideStanding.IN_FORCE);
	}

	@Test
	void itIsPendingUpToTheDayBeforeItStartsAndInForceOnTheDayItself() {
		assertThat(at(OCT_1, null, null, OCT_1.minusDays(1))).isEqualTo(OverrideStanding.PENDING);
		assertThat(at(OCT_1, null, null, OCT_1)).isEqualTo(OverrideStanding.IN_FORCE);
	}

	/** c4 — the expiry day is inclusive, so ENDED begins the day after. */
	@Test
	void itIsInForceOnItsExpiryDayAndEndedTheDayAfter() {
		assertThat(at(null, DEC_31, null, DEC_31)).isEqualTo(OverrideStanding.IN_FORCE);
		assertThat(at(null, DEC_31, null, DEC_31.plusDays(1))).isEqualTo(OverrideStanding.ENDED);
	}

	@Test
	void aSingleDayWindowIsInForceForExactlyThatDay() {
		assertThat(at(OCT_1, OCT_1, null, OCT_1.minusDays(1))).isEqualTo(OverrideStanding.PENDING);
		assertThat(at(OCT_1, OCT_1, null, OCT_1)).isEqualTo(OverrideStanding.IN_FORCE);
		assertThat(at(OCT_1, OCT_1, null, OCT_1.plusDays(1))).isEqualTo(OverrideStanding.ENDED);
	}

	/** Removal outranks the window: it stops there rather than running on to its expiry. */
	@Test
	void removalOutranksTheWindowFromTheDayItHappens() {
		LocalDate removedOn = LocalDate.of(2026, 11, 15);

		assertThat(at(OCT_1, DEC_31, removedOn, removedOn.minusDays(1))).isEqualTo(OverrideStanding.IN_FORCE);
		assertThat(at(OCT_1, DEC_31, removedOn, removedOn)).isEqualTo(OverrideStanding.REMOVED);
	}

	@Test
	void aPendingOverrideRemovedBeforeItBeganIsRemovedRatherThanPending() {
		LocalDate removedOn = LocalDate.of(2026, 9, 1);

		assertThat(at(OCT_1, DEC_31, removedOn, LocalDate.of(2026, 9, 15))).isEqualTo(OverrideStanding.REMOVED);
	}

	@Test
	void onlyInForceCounts() {
		assertThat(OverrideStanding.IN_FORCE.counts()).isTrue();
		assertThat(OverrideStanding.PENDING.counts()).isFalse();
		assertThat(OverrideStanding.ENDED.counts()).isFalse();
		assertThat(OverrideStanding.REMOVED.counts()).isFalse();
	}

	@Test
	void theDateItStoppedCountingIsTheDayAfterAnInclusiveExpiry() {
		assertThat(OverrideStanding.notInForceSince(
				OverrideStanding.ENDED, Optional.of(DEC_31), Optional.empty()))
				.contains(LocalDate.of(2027, 1, 1));
	}

	@Test
	void theDateItStoppedCountingIsTheRemovalDayForARemovedOverride() {
		LocalDate removedOn = LocalDate.of(2026, 11, 15);

		assertThat(OverrideStanding.notInForceSince(
				OverrideStanding.REMOVED, Optional.of(DEC_31), Optional.of(removedOn)))
				.contains(removedOn);
	}

	@Test
	void thereIsNoSuchDateForOneStillCountingOrNotYetBegun() {
		assertThat(OverrideStanding.notInForceSince(OverrideStanding.IN_FORCE, Optional.of(DEC_31), Optional.empty()))
				.isEmpty();
		assertThat(OverrideStanding.notInForceSince(OverrideStanding.PENDING, Optional.of(DEC_31), Optional.empty()))
				.isEmpty();
	}
}
