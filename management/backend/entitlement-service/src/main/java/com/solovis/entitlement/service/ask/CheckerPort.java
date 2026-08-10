package com.solovis.entitlement.service.ask;

/**
 * Seam to the classic checker. The implementation must return exactly the payload of
 * {@code GET /admin/v1/check?account=&capability=} — same DTO, same code path — so the ask
 * feature adds interpretation around the checker, never a second implementation of it
 * (001 criterion 24; 003 criteria 1 and 3).
 *
 * <p>TODO(003): implement against the checker service from the api-layer worktree once it
 * merges, and replace {@link Object} with its response DTO. Until then no bean exists and
 * asking answers 503 — the designed safe-off state.
 */
public interface CheckerPort {

	Object explain(String accountExternalId, String capabilityKey);
}
