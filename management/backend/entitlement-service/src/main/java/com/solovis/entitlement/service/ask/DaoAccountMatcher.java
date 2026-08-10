package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.store.AccountRow;
import com.solovis.entitlement.service.store.DecisionReadDao;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic mention resolution: exact external id, then exact name, then contains-search.
 * Thresholds live here alone; tuning them never touches the interpreter.
 *
 * <p>Both reads go through {@link DecisionReadDao} on the read pool, and both are ACTIVE-only —
 * {@code dao.account(...)} already filters, and {@code searchAccounts} mirrors it. A CLOSED
 * account must never resolve here: matching one would confidently answer for an account that
 * {@code /v1} itself would reject as unknown.
 *
 * <p>TODO(003): the contains step's LIKE is case-insensitive for ASCII only; the plan calls for
 * an indexed lower(name) column when this is productionised.
 */
@Component
public class DaoAccountMatcher implements AccountMatcher {

	static final int MAX_CANDIDATES = 8;

	private final DecisionReadDao dao;

	public DaoAccountMatcher(DecisionReadDao dao) {
		this.dao = dao;
	}

	@Override
	public AccountMatch match(String mention) {
		var byExternalId = dao.account(mention);
		if (byExternalId.isPresent()) {
			return new AccountMatch.One(byExternalId.get());
		}

		List<AccountRow> hits = dao.searchAccounts(mention, MAX_CANDIDATES + 1);

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
