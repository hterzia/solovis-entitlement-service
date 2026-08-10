package com.solovis.entitlement.core.model;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Whether an override counts, at one moment (002 spec §3.2).
 *
 * <p>Standing is a statement about a <em>date</em>, not a property of the override — the same
 * record is pending in September and ended in January. That is why it lives here rather than on
 * {@link AccountOverride}: folding it in would make the record's meaning depend on when it was
 * read.
 *
 * <p>{@link #of} is the single rule. The repository filtering a decision, the API reporting an
 * account's overrides, and the UI grouping them all resolve through it, so they cannot drift.
 */
public enum OverrideStanding {

	/** Its start date has not arrived. Visible, so a promise made in advance is not forgotten. */
	PENDING,

	/** The ordinary state, and the only one that takes part in a decision (c10). */
	IN_FORCE,

	/** Its expiry date has passed. Never cleared by the system (c16). */
	ENDED,

	/** A person removed it. The record survives, marked (c17). */
	REMOVED;

	public boolean counts() {
		return this == IN_FORCE;
	}

	/**
	 * @param startsOn  first day in force, or empty for "from creation"
	 * @param expiresOn last day in force <em>inclusive</em>, or empty for "until removed"
	 * @param removedOn the day a person removed it, or empty
	 * @param asOf      the date being asked about, in the service zone
	 */
	public static OverrideStanding of(Optional<LocalDate> startsOn, Optional<LocalDate> expiresOn,
			Optional<LocalDate> removedOn, LocalDate asOf) {
		// Removal outranks the window: an override removed while pending never begins, and one
		// removed mid-window stops there rather than running to its expiry.
		if (removedOn.isPresent() && !asOf.isBefore(removedOn.get())) {
			return REMOVED;
		}
		if (startsOn.isPresent() && asOf.isBefore(startsOn.get())) {
			return PENDING;
		}
		// isAfter, not !isBefore: the expiry day is inclusive (c4).
		if (expiresOn.isPresent() && asOf.isAfter(expiresOn.get())) {
			return ENDED;
		}
		return IN_FORCE;
	}

	/**
	 * The date this override stopped counting, for the ones that have — the day after an inclusive
	 * expiry, or the day of removal. Empty for {@link #IN_FORCE}, and for {@link #PENDING}, whose
	 * date is its own {@code startsOn}.
	 */
	public static Optional<LocalDate> notInForceSince(OverrideStanding standing,
			Optional<LocalDate> expiresOn, Optional<LocalDate> removedOn) {
		return switch (standing) {
			case REMOVED -> removedOn;
			case ENDED -> expiresOn.map(day -> day.plusDays(1));
			case IN_FORCE, PENDING -> Optional.empty();
		};
	}
}
