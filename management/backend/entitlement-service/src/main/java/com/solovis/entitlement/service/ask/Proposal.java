package com.solovis.entitlement.service.ask;

import java.util.List;

/**
 * What the interpreter believes the question mentions. A proposal is never trusted as-is:
 * {@link AskService} verifies every part against local records before anything runs, so an
 * account or capability the interpreter names must actually exist or the question is unmatched.
 *
 * @param accountMention    the exact words the operator used for the account, or null if the
 *                          question names none
 * @param capabilityKeys    0–3 catalogue keys that plausibly match, best first
 * @param capabilityMention the words the operator used for the capability, kept so an empty
 *                          key list can still say what was not matched
 * @param dateMention       the operator's words for a moment in time, or null if the question
 *                          names none — absence is never treated as a date (spec c16)
 * @param resolvedDate      an ISO date ({@code YYYY-MM-DD}) if {@code dateMention} pins down one
 *                          specific day, or null if the words are too vague to (spec c18); never
 *                          set without {@code dateMention} also being set
 */
public record Proposal(String accountMention, List<String> capabilityKeys, String capabilityMention,
		String dateMention, String resolvedDate) {

	public Proposal {
		capabilityKeys = capabilityKeys == null ? List.of() : List.copyOf(capabilityKeys);
	}

	/** Convenience for callers that never mention a date — every T1–T5 test uses this shape. */
	public Proposal(String accountMention, List<String> capabilityKeys, String capabilityMention) {
		this(accountMention, capabilityKeys, capabilityMention, null, null);
	}
}
