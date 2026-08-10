package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.ask.dto.AskResponse;
import com.solovis.entitlement.service.store.AccountRow;

import java.util.ArrayList;
import java.util.List;

/**
 * The only class that touches both sides of the one-way flow: interpret → verify locally →
 * run the classic checker → respond. The interpreter's proposal survives only where local
 * records agree with it.
 */
public class AskService {

	private final QuestionInterpreter interpreter; // null until a Gemini api-key is configured
	private final CheckerPort checker;             // null until the api-layer checker merges
	private final AccountMatcher accountMatcher;
	private final CapabilityCatalogProvider catalogs;

	public AskService(QuestionInterpreter interpreter, CheckerPort checker,
			AccountMatcher accountMatcher, CapabilityCatalogProvider catalogs) {
		this.interpreter = interpreter;
		this.checker = checker;
		this.accountMatcher = accountMatcher;
		this.catalogs = catalogs;
	}

	public boolean available() {
		return interpreter != null && checker != null;
	}

	public AskResponse ask(String question) {
		if (!available()) {
			throw new AskUnavailableException("The plain-English checker is not configured");
		}

		CapabilityCatalog catalog = catalogs.current();
		Proposal proposal = interpreter.interpret(question, catalog);

		// Criterion 10: keys the interpreter proposed but the registry does not know are dropped.
		List<String> keys = proposal.capabilityKeys().stream()
				.distinct()
				.filter(catalog::containsKey)
				.toList();

		if (keys.isEmpty()) {
			return capabilityNotUnderstood(proposal);
		}

		String accountMention = proposal.accountMention();
		if (accountMention == null || accountMention.isBlank()) {
			return AskResponse.noMatch(null, null, "Tell me which account you mean.");
		}
		String mention = accountMention.trim();

		return switch (accountMatcher.match(mention)) {
			case AccountMatch.One(AccountRow account) -> {
				AskResponse.AccountRef ref = new AskResponse.AccountRef(account.externalId(), account.name());
				if (keys.size() == 1) {
					yield AskResponse.answered(ref, keys.getFirst(),
							checker.explain(account.externalId(), keys.getFirst()));
				}
				// Account resolved, capability ambiguous: the pick runs the classic check.
				yield AskResponse.clarify(mention, List.of(ref), null, keys);
			}
			case AccountMatch.Candidates(List<AccountRow> accounts) -> AskResponse.clarify(
					mention,
					accounts.stream()
							.map(row -> new AskResponse.AccountRef(row.externalId(), row.name()))
							.toList(),
					keys.size() == 1 ? keys.getFirst() : null,
					keys.size() == 1 ? null : keys);
			case AccountMatch.TooMany() -> AskResponse.noMatch(mention, null,
					"Several accounts match '%s' — be more specific.".formatted(mention));
			case AccountMatch.None() -> AskResponse.noMatch(mention, null,
					"No account matching '%s'.".formatted(mention));
		};
	}

	private AskResponse capabilityNotUnderstood(Proposal proposal) {
		// Retirement is a fact worth stating (criterion 7), checked against local records only.
		List<String> mentions = new ArrayList<>(proposal.capabilityKeys());
		if (proposal.capabilityMention() != null) {
			mentions.add(proposal.capabilityMention());
		}
		return catalogs.retiredMatch(mentions)
				.map(AskResponse::retired)
				.orElseGet(() -> {
					String mention = proposal.capabilityMention();
					return AskResponse.noMatch(null, mention, mention == null
							? "Tell me which capability you mean."
							: "Nothing in the registry matches '%s'.".formatted(mention));
				});
	}
}
