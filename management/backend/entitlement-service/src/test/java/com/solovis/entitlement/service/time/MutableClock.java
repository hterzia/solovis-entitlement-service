package com.solovis.entitlement.service.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A clock a test can move.
 *
 * <p>002's definition of done requires criteria 11, 12 and 13 to be demonstrated <em>by letting the
 * clock reach a boundary</em> rather than by editing anything, which is only possible if the clock
 * is a dependency. It is — {@code ClockConfig} is the only place the wall clock is read, and
 * {@code NoDirectClockAccessTest} keeps it that way.
 *
 * <p>{@link #advanceTo} lands at midday rather than midnight on purpose. Every question this clock
 * is asked is "which date is it in the service zone", and midday is unambiguous on both daylight
 * saving days, so a test that moves across 14 March or 7 November cannot be tripped by the very
 * hour it exists to check.
 */
public final class MutableClock extends Clock {

	private final ZoneId zone;
	private volatile Instant instant;

	public MutableClock(LocalDate date, ZoneId zone) {
		this.zone = zone;
		this.instant = middayOf(date, zone);
	}

	private MutableClock(Instant instant, ZoneId zone) {
		this.zone = zone;
		this.instant = instant;
	}

	private static Instant middayOf(LocalDate date, ZoneId zone) {
		return date.atStartOfDay(zone).plusHours(12).toInstant();
	}

	/** Moves to midday of {@code date} in the service zone. */
	public void advanceTo(LocalDate date) {
		this.instant = middayOf(date, zone);
	}

	@Override
	public Instant instant() {
		return instant;
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId other) {
		return new MutableClock(instant, other);
	}
}
