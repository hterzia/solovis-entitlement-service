package com.solovis.entitlement.service.time;

import com.solovis.entitlement.service.config.EntitlementClockProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One clock and one zone for the whole service (002 spec §3.1, c5).
 *
 * <p>The {@link Clock} carries the zone rather than being {@code systemUTC()}, so that
 * {@code LocalDate.now(clock)} anywhere in the service yields the operator-facing date rather than
 * the host's. Stored timestamps remain ISO-8601 UTC instants — the zone governs how a <em>date</em>
 * is interpreted, never how a moment is written down.
 *
 * <p>Every {@code now()} in the service comes through this bean, enforced by
 * {@code NoDirectClockAccessTest}. That is not tidiness: 002's boundary criteria (c11–c13) are
 * demonstrated by moving the clock across a midnight, and a single direct call to the wall clock
 * makes such a test flaky rather than failing.
 */
@Configuration
@EnableConfigurationProperties(EntitlementClockProperties.class)
public class ClockConfig {

	@Bean
	public ZoneId entitlementZone(EntitlementClockProperties properties) {
		ZoneId zone = ZoneId.of(properties.zone());
		return ServiceZone.validated(zone, LocalDate.now(zone));
	}

	/**
	 * The one construction of a wall clock in the service, extracted so {@code SeedClockConfig} can
	 * decorate it without repeating it — and without putting a second wall-clock call in a file
	 * {@code NoDirectClockAccessTest} does not exempt.
	 *
	 * <p>Ticks in whole milliseconds because every stored and published timestamp is ISO-8601 with
	 * millisecond precision (contracts/README.md). Truncating at the source rather than at each
	 * formatting site is what stops a microsecond tail reaching the wire.
	 */
	public static Clock base(ZoneId zone) {
		return Clock.tick(Clock.system(zone), Duration.ofMillis(1));
	}

	// Mirrored by SeedClockConfig, which decorates this with a windable Clock while the demo seed
	// runs. The two conditions are exhaustive and mutually exclusive: exactly one Clock bean exists.
	@Bean
	@ConditionalOnProperty(name = "entitlement.seed.enabled", havingValue = "false", matchIfMissing = true)
	public Clock clock(ZoneId entitlementZone) {
		return base(entitlementZone);
	}
}
