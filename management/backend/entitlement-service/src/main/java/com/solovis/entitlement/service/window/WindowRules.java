package com.solovis.entitlement.service.window;

import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;

import java.time.LocalDate;

/**
 * The three refusals of 002 c7, in one place because the API and any future importer must apply
 * them identically.
 *
 * <p>None is expressible as a column constraint: SQLite cannot add a CHECK to an existing table,
 * and two of the three compare against the clock, which no constraint can see.
 *
 * <p>The no-back-dating rules deserve their reasoning stated, because they refuse something users
 * will ask for. A window that began before it was saved would assert that an override applied at a
 * time when it did not, and 002 makes the past something this service <em>answers questions
 * about</em> — so a past answer must not be able to change after the fact. An agreement reached on
 * the 1st but entered on the 9th took effect on the 9th, and the reason text is where that
 * discrepancy belongs (002 spec §3.1, and its known-limitations table).
 *
 * <p>The demo seeder is the one component permitted to write backdated rows, because it is
 * manufacturing a fictional past rather than asserting a real one. It writes to the repository
 * directly and deliberately does not come through here.
 */
public final class WindowRules {

	private WindowRules() {
	}

	/**
	 * @param startsOn  the window's first day, or null for "from creation"
	 * @param expiresOn the window's last day, inclusive, or null for "until removed"
	 * @param today     the current date in the service zone
	 */
	public static void validate(LocalDate startsOn, LocalDate expiresOn, LocalDate today) {
		if (startsOn != null && expiresOn != null && startsOn.isAfter(expiresOn)) {
			throw new EntitlementApiException(ErrorCode.INVALID_WINDOW,
					"An override cannot start (" + startsOn + ") after it expires (" + expiresOn + ").");
		}
		if (startsOn != null && startsOn.isBefore(today)) {
			throw new EntitlementApiException(ErrorCode.INVALID_WINDOW,
					"An override cannot start in the past (" + startsOn + ", today is " + today
							+ "). It takes effect when it is saved; say so in the reason.");
		}
		if (expiresOn != null && expiresOn.isBefore(today)) {
			throw new EntitlementApiException(ErrorCode.INVALID_WINDOW,
					"An override cannot expire in the past (" + expiresOn + ", today is " + today
							+ "). Creating one already ended would assert that it applied when it did not.");
		}
	}
}
