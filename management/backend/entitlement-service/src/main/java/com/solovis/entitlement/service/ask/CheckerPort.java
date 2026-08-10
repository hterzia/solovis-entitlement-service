package com.solovis.entitlement.service.ask;

/**
 * Seam to the classic checker. The implementation must return exactly the body of
 * {@code GET /admin/v1/check} — same DTO, same code path — so the ask feature adds
 * interpretation around the checker, never a second implementation of it (001 criterion 24;
 * 003 criteria 1 and 3).
 *
 * <p>{@code asAt} is {@code null} for a present-tense question. {@code Object} is deliberate,
 * not a placeholder: the checker returns {@code DecisionResponseDto} for a present-tense
 * question and {@code AsAtDecisionResponseDto} for a past one. The two have no common Java
 * supertype, but {@code AsAtDecisionResponseDto} {@code @JsonUnwraps} the decision, so on the
 * wire a past answer is the ordinary shape plus {@code asAt} and
 * {@code capabilityRetiredSince}. Ask carries the body through untouched and never reads a
 * field off it — so it needs no type, and narrowing this would throw
 * {@code ClassCastException} on every past-dated question.
 */
public interface CheckerPort {

	Object explain(String accountExternalId, String capabilityKey, String asAt);
}
