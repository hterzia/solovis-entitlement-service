package com.solovis.entitlement.service.ask;

/** Resolves an account mention to real records — always locally, never via the model (spec §4). */
public interface AccountMatcher {

	AccountMatch match(String mention);
}
