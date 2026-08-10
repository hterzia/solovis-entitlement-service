package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.ask.dto.AskResponse;
import com.solovis.entitlement.service.store.AccountRow;

import java.time.Clock;
import java.time.LocalDate;
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
	private final Clock clock; // zone-carrying (002's bean) — LocalDate.now(clock) is the Eastern date

	public AskService(QuestionInterpreter interpreter, CheckerPort checker,
			AccountMatcher accountMatcher, CapabilityCatalogProvider catalogs, Clock clock) {
		this.interpreter = interpreter;
		this.checker = checker;
		this.accountMatcher = accountMatcher;
		this.catalogs = catalogs;
		this.clock = clock;
	}

	public boolean available() {
		return interpreter != null && checker != null;
	}

	public AskResponse ask(String question) {
		if (!available()) {
			throw new AskUnavailableException("The plain-English checker is not configured");
		}

		LocalDate today = LocalDate.now(clock);
		CapabilityCatalog catalog = catalogs.current();
		Proposal proposal = interpreter.interpret(question, catalog, today);

		// Criterion 10: keys the interpreter proposed but the registry does not know are dropped.
		// The catalogue carries every status, so a retired key the interpreter names survives here —
		// retirement is decided below, from the same catalogue entry, never by dropping the key.
		List<String> keys = proposal.capabilityKeys().stream()
				.distinct()
				.filter(catalog::containsKey)
				.toList();

		if (keys.isEmpty()) {
			return capabilityNotUnderstood(proposal, catalog);
		}

		if (keys.size() == 1 && catalog.find(keys.getFirst()).map(CapabilityCatalog.Entry::retired).orElse(false)) {
			// Criterion 7: retirement is a fact worth stating, decided locally and before any
			// account lookup or check — asking about a retired capability never reaches the checker.
			return AskResponse.retired(keys.getFirst());
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
					// asAt is always null until T7 adds date resolution — every question is about now.
					yield AskResponse.answered(ref, keys.getFirst(),
							checker.explain(account.externalId(), keys.getFirst(), null));
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

	private AskResponse capabilityNotUnderstood(Proposal proposal, CapabilityCatalog catalog) {
		// Reached only when the interpreter proposed no key the catalogue recognises at all — a
		// proposed retired key already survived the filter above. The mention text is still worth
		// checking against the catalogue: an operator's words ("legacy export") can name a retired
		// capability the model failed to key, and retirement is a fact worth stating (criterion 7).
		String mention = proposal.capabilityMention();
		if (mention != null) {
			for (CapabilityCatalog.Entry entry : catalog.entries()) {
				boolean matchesText = entry.key().equalsIgnoreCase(mention)
						|| (entry.displayName() != null && entry.displayName().equalsIgnoreCase(mention));
				if (matchesText && entry.retired()) {
					return AskResponse.retired(entry.key());
				}
			}
		}
		return AskResponse.noMatch(null, mention, mention == null
				? "Tell me which capability you mean."
				: "Nothing in the registry matches '%s'.".formatted(mention));
	}
}
