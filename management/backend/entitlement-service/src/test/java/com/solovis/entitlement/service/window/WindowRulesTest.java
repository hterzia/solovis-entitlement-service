package com.solovis.entitlement.service.window;

import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 002 c7 — the three windows that cannot be saved, and the several that can. */
class WindowRulesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

	@Test
	void anOpenEndedOverrideIsStillTheOrdinaryCase() {
		assertThatCode(() -> WindowRules.validate(null, null, TODAY)).doesNotThrowAnyException();
	}

	@Test
	void aWindowStartingTodayAndRunningForwardIsAccepted() {
		assertThatCode(() -> WindowRules.validate(TODAY, TODAY.plusDays(90), TODAY)).doesNotThrowAnyException();
	}

	@Test
	void aWindowOfASingleDayIsAcceptedBecauseTheExpiryDayIsInclusive() {
		assertThatCode(() -> WindowRules.validate(TODAY, TODAY, TODAY)).doesNotThrowAnyException();
	}

	@Test
	void anExpiryTodayIsAcceptedBecauseTodayIsNotYetOver() {
		assertThatCode(() -> WindowRules.validate(null, TODAY, TODAY)).doesNotThrowAnyException();
	}

	@Test
	void aFutureStartWithNoExpiryIsAccepted() {
		assertThatCode(() -> WindowRules.validate(TODAY.plusMonths(2), null, TODAY)).doesNotThrowAnyException();
	}

	@Test
	void aStartAfterItsExpiryDescribesNothingAndIsRefused() {
		assertThatThrownBy(() -> WindowRules.validate(TODAY.plusDays(10), TODAY.plusDays(3), TODAY))
				.isInstanceOf(EntitlementApiException.class)
				.hasMessageContaining("cannot start")
				.extracting(e -> ((EntitlementApiException) e).errorCode())
				.isEqualTo(ErrorCode.INVALID_WINDOW);
	}

	@Test
	void aBackDatedStartIsRefusedSoThatAPastAnswerCannotChangeAfterTheFact() {
		assertThatThrownBy(() -> WindowRules.validate(TODAY.minusDays(1), TODAY.plusDays(30), TODAY))
				.isInstanceOf(EntitlementApiException.class)
				.hasMessageContaining("start in the past");
	}

	@Test
	void aWindowWhollyInThePastIsRefusedBecauseItWouldAssertSomethingThatNeverApplied() {
		assertThatThrownBy(() -> WindowRules.validate(null, TODAY.minusDays(1), TODAY))
				.isInstanceOf(EntitlementApiException.class)
				.hasMessageContaining("expire in the past");
	}

	@Test
	void everyRefusalCarriesTheSameStableSlugSoCallersCanBranchOnIt() {
		assertThat(ErrorCode.INVALID_WINDOW.type()).isEqualTo("entitlement/invalid-window");
	}
}
