package com.solovis.entitlement.service.ask.dto;

import java.util.List;

/**
 * One response shape, four statuses. Jackson is configured non_null, so each status serialises
 * only its own fields. {@code result} is exactly the {@code GET /admin/v1/check} payload —
 * TODO(003): narrow from {@link Object} to the checker's DTO once the api layer merges.
 */
public record AskResponse(
		String status,
		Interpretation interpretation,
		Object result,
		List<AccountRef> accountCandidates,
		List<String> capabilityCandidates,
		Unmatched unmatched,
		String detail) {

	public static final String ANSWERED = "ANSWERED";
	public static final String CLARIFY = "CLARIFY";
	public static final String NO_MATCH = "NO_MATCH";
	public static final String RETIRED_CAPABILITY = "RETIRED_CAPABILITY";

	public record AccountRef(String external, String name) {
	}

	/** What was understood — displayed with every answer so a misread question is visible. */
	public record Interpretation(AccountRef account, String accountMention, String capability) {
	}

	public record Unmatched(String accountMention, String capabilityMention) {
	}

	public static AskResponse answered(AccountRef account, String capability, Object result) {
		return new AskResponse(ANSWERED, new Interpretation(account, null, capability), result,
				null, null, null, null);
	}

	public static AskResponse clarify(String accountMention, List<AccountRef> accountCandidates,
			String capability, List<String> capabilityCandidates) {
		return new AskResponse(CLARIFY, new Interpretation(null, accountMention, capability), null,
				accountCandidates, capabilityCandidates, null, null);
	}

	public static AskResponse noMatch(String accountMention, String capabilityMention, String detail) {
		return new AskResponse(NO_MATCH, null, null, null, null,
				new Unmatched(accountMention, capabilityMention), detail);
	}

	public static AskResponse retired(String capabilityKey) {
		return new AskResponse(RETIRED_CAPABILITY, new Interpretation(null, null, capabilityKey), null,
				null, null, null,
				"Capability '%s' is retired and no longer evaluable.".formatted(capabilityKey));
	}
}
