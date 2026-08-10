package com.solovis.entitlement.core.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * An override paired with what it was doing at one moment.
 *
 * <p>The pairing exists because the two are different kinds of fact: an {@link AccountOverride} is
 * a statement of what was agreed, and {@link OverrideStanding} is a statement about a date. An
 * explanation needs both — *"GRANT 200, ended 30 June"* — and needs them to have been computed once
 * rather than re-derived by every reader.
 *
 * @param standing        what this override was doing at the moment asked about
 * @param notInForceSince the date it stopped counting, where that is meaningful (see
 *                        {@link OverrideStanding#notInForceSince})
 */
public record StandingOverride(
		AccountOverride override,
		OverrideStanding standing,
		Optional<LocalDate> notInForceSince) {

	public StandingOverride {
		Objects.requireNonNull(override, "override");
		Objects.requireNonNull(standing, "standing");
		Objects.requireNonNull(notInForceSince, "notInForceSince");
	}

	/** Computes standing from the override's own window plus a removal date, at {@code asOf}. */
	public static StandingOverride at(AccountOverride override, Optional<LocalDate> removedOn, LocalDate asOf) {
		var standing = OverrideStanding.of(override.startsOn(), override.expiresOn(), removedOn, asOf);
		return new StandingOverride(override, standing,
				OverrideStanding.notInForceSince(standing, override.expiresOn(), removedOn));
	}

	/**
	 * An override known to be counting — the shape a replica's answer-only projection produces,
	 * where windows were evaluated before publication and nothing not-in-force is present at all.
	 */
	public static StandingOverride inForce(AccountOverride override) {
		return new StandingOverride(override, OverrideStanding.IN_FORCE, Optional.empty());
	}

	public boolean counts() {
		return standing.counts();
	}
}
