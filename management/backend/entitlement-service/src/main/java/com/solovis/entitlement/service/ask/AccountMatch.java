package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.store.AccountRow;

import java.util.List;

/** Outcome of resolving an account mention locally. */
public sealed interface AccountMatch {

	record One(AccountRow account) implements AccountMatch {
	}

	/** 2–8 plausible accounts: the operator picks; the system never does (spec criterion 6). */
	record Candidates(List<AccountRow> accounts) implements AccountMatch {
	}

	/** More matches than a pick-list is worth — answered as "be more specific". */
	record TooMany() implements AccountMatch {
	}

	record None() implements AccountMatch {
	}
}
