package com.solovis.entitlement.service.store;

/**
 * One {@code account_override} row.
 *
 * <p>{@code startsOn} and {@code expiresOn} are 'YYYY-MM-DD' in the service zone, both nullable
 * (002 spec §3.1). They are appended rather than placed beside the other value columns so that
 * every pre-existing positional construction keeps its meaning.
 */
public record AccountOverrideRow(
		Long id,
		long accountId,
		long capabilityId,
		String kind,
		Boolean boolValue,
		Long qtyValue,
		boolean qtyUnlimited,
		String tierValue,
		String reason,
		String createdAt,
		String createdBy,
		String createdSource,
		String removedAt,
		String removedBy,
		String removedReason,
		String startsOn,
		String expiresOn) {

	/** An open-ended override — no start, no expiry — which is still the ordinary case. */
	public static AccountOverrideRow openEnded(
			Long id, long accountId, long capabilityId, String kind,
			Boolean boolValue, Long qtyValue, boolean qtyUnlimited, String tierValue,
			String reason, String createdAt, String createdBy, String createdSource,
			String removedAt, String removedBy, String removedReason) {
		return new AccountOverrideRow(id, accountId, capabilityId, kind, boolValue, qtyValue, qtyUnlimited,
				tierValue, reason, createdAt, createdBy, createdSource, removedAt, removedBy, removedReason,
				null, null);
	}
}
