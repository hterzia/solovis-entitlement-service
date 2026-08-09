package com.solovis.entitlement.service.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;

/**
 * Guards the one property the whole-day window model depends on: <strong>midnight must name exactly
 * one instant</strong>.
 *
 * <p>An override's start begins at 00:00 of its date and its expiry ends at 00:00 of the day after
 * (002 spec §3.1, c4), so every boundary in the system is a midnight. In a zone that skips or
 * repeats midnight, a boundary would name either no instant or two, and a stored window would stop
 * meaning one thing.
 *
 * <p>US Eastern satisfies this because its transitions fall at 02:00 — but that is a fact about
 * that zone, not about zones generally: Cuba transitions at midnight, and several South American
 * zones have historically done so. So this is asserted at startup rather than assumed, which is
 * what would catch a later change of {@code entitlement.clock.zone} to somewhere this design does
 * not work. Failing to boot is the intended outcome; the alternative is answering wrongly twice a
 * year in a way nobody would trace back to a configuration value.
 */
public final class ServiceZone {

	/**
	 * Years either side of today the check covers. The change history is retained for seven years
	 * (002 c32) and windows are written into the future, so eight in both directions covers every
	 * date a boundary could fall on and leaves a margin.
	 */
	static final int HORIZON_YEARS = 8;

	private ServiceZone() {
	}

	/** The configured zone, or {@link IllegalStateException} naming the dates that make it unusable. */
	public static ZoneId validated(ZoneId zone, LocalDate today) {
		List<LocalDate> ambiguous = datesWithAmbiguousMidnight(
				zone, today.minusYears(HORIZON_YEARS), today.plusYears(HORIZON_YEARS));
		if (!ambiguous.isEmpty()) {
			throw new IllegalStateException(
					"Zone '" + zone.getId() + "' cannot be the service clock: midnight is skipped or repeated on "
							+ ambiguous + ". Override windows are whole days (002 spec §3.1) and need midnight "
							+ "to name exactly one instant.");
		}
		return zone;
	}

	/**
	 * Dates in the window whose local midnight has no valid offset (skipped) or two (repeated).
	 *
	 * <p>Only a date carrying an offset transition can be affected, so the zone's transitions are
	 * walked rather than every date in sixteen years being tested. A fixed-offset zone has none and
	 * returns immediately.
	 */
	static List<LocalDate> datesWithAmbiguousMidnight(ZoneId zone, LocalDate from, LocalDate to) {
		var rules = zone.getRules();
		var offending = new ArrayList<LocalDate>();
		var end = to.atStartOfDay(ZoneOffset.UTC).toInstant();
		for (ZoneOffsetTransition transition = rules.nextTransition(from.atStartOfDay(ZoneOffset.UTC).toInstant());
				transition != null && transition.getInstant().isBefore(end);
				transition = rules.nextTransition(transition.getInstant())) {
			for (LocalDate candidate : List.of(
					transition.getDateTimeBefore().toLocalDate(),
					transition.getDateTimeAfter().toLocalDate())) {
				boolean ambiguous = rules.getValidOffsets(LocalDateTime.of(candidate, LocalTime.MIDNIGHT)).size() != 1;
				if (ambiguous && !offending.contains(candidate)) {
					offending.add(candidate);
				}
			}
		}
		return offending;
	}
}
