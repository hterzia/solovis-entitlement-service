package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.AccountRow;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic mention resolution: exact external id, then exact name, then contains-search.
 * Thresholds live here alone; tuning them never touches the interpreter.
 *
 * <p>TODO(003): the contains step rides on {@link AccountRepository#search}, whose LIKE is
 * case-insensitive for ASCII only; the plan calls for an indexed lower(name) column when this
 * is productionised.
 */
@Component
public class DbAccountMatcher implements AccountMatcher {

	static final int MAX_CANDIDATES = 8;

	private final AccountRepository accounts;

	public DbAccountMatcher(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Override
	public AccountMatch match(String mention) {
		var byExternalId = accounts.findByExternalId(mention);
		if (byExternalId.isPresent()) {
			return new AccountMatch.One(byExternalId.get());
		}

		List<AccountRow> hits = accounts.search(mention, null, 0, MAX_CANDIDATES + 1);

		List<AccountRow> exactName = hits.stream()
				.filter(row -> row.name() != null && row.name().equalsIgnoreCase(mention))
				.toList();
		if (exactName.size() == 1) {
			return new AccountMatch.One(exactName.getFirst());
		}

		if (hits.isEmpty()) {
			return new AccountMatch.None();
		}
		if (hits.size() == 1) {
			return new AccountMatch.One(hits.getFirst());
		}
		if (hits.size() > MAX_CANDIDATES) {
			return new AccountMatch.TooMany();
		}
		return new AccountMatch.Candidates(hits);
	}
}
