package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.time.ClockConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SeedClockTest {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private final SeedClock clock = new SeedClock(ClockConfig.base(EASTERN));

    @Test
    void unwoundItReadsTheRealClock() {
        assertThat(clock.isWound()).isFalse();
        assertThat(clock.instant()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void woundItReadsTheAuthoredInstant() {
        Instant authored = Instant.parse("2026-03-14T09:15:00Z");

        clock.windTo(authored);

        assertThat(clock.isWound()).isTrue();
        assertThat(clock.instant()).isEqualTo(authored);
    }

    @Test
    void releasedItReadsTheRealClockAgain() {
        clock.windTo(Instant.parse("2026-03-14T09:15:00Z"));

        clock.release();

        assertThat(clock.isWound()).isFalse();
        assertThat(clock.instant()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    /**
     * The zone is not cosmetic: it is what makes {@code LocalDate.now(clock)} the operator-facing
     * date, and therefore what every override window means. A SeedClock over UTC would compile and
     * pass every other test here while shifting window boundaries by hours.
     */
    @Test
    void itKeepsTheServiceZoneWoundAndUnwound() {
        assertThat(clock.getZone()).isEqualTo(EASTERN);

        clock.windTo(Instant.parse("2026-03-14T09:15:00Z"));

        assertThat(clock.getZone()).isEqualTo(EASTERN);
        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 3, 14));
    }
}
