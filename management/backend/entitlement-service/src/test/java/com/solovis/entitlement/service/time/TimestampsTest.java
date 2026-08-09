package com.solovis.entitlement.service.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Instant#toString()} omits the fractional part entirely when the instant lands exactly on
 * a whole second, which breaks the contract's promise (contracts/README.md, "All bodies are...")
 * that every timestamp is ISO-8601 UTC with milliseconds. {@link Timestamps#iso} must always render
 * exactly three fractional digits, whole second or not.
 */
class TimestampsTest {

    @Test
    void wholeSecondInstantStillRendersThreeFractionalDigits() {
        Instant wholeSecond = Instant.parse("2026-08-09T14:03:11.482Z").truncatedTo(ChronoUnit.SECONDS);

        assertThat(Timestamps.iso(wholeSecond)).isEqualTo("2026-08-09T14:03:11.000Z");
    }

    @Test
    void subMillisecondInstantRendersItsThreeDigitFraction() {
        Instant instant = Instant.parse("2026-08-09T14:03:11.482Z");

        assertThat(Timestamps.iso(instant)).isEqualTo("2026-08-09T14:03:11.482Z");
    }

    @Test
    void alwaysEndsInZAndHasExactlyThreeFractionalDigits() {
        Instant wholeSecond = Instant.parse("2026-08-09T00:00:00.000Z");
        Instant fractional = Instant.parse("2026-08-09T00:00:00.007Z");

        assertThat(Timestamps.iso(wholeSecond)).matches(".*\\.\\d{3}Z");
        assertThat(Timestamps.iso(fractional)).matches(".*\\.\\d{3}Z");
    }
}
