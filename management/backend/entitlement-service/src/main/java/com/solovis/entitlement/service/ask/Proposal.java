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
 */
public record Proposal(String accountMention, List<String> capabilityKeys, String capabilityMention) {

	public Proposal {
		capabilityKeys = capabilityKeys == null ? List.of() : List.copyOf(capabilityKeys);
	}
}
