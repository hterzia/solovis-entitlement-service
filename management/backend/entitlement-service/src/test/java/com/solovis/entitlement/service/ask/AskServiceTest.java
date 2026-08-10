package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.ask.dto.AskResponse;
import com.solovis.entitlement.service.store.AccountRow;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AskServiceTest {

	private static final CapabilityCatalog CATALOG = new CapabilityCatalog(List.of(
			new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export", false),
			new CapabilityCatalog.Entry("export.pdf", "export", "PDF export", false),
			new CapabilityCatalog.Entry("api.access", "api", "API access", false),
			new CapabilityCatalog.Entry("export.csv", "export", "CSV export", true)));

	private static final Object CHECK_PAYLOAD = new Object();

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);

	private static AccountRow account(String externalId, String name) {
		return new AccountRow(1L, externalId, name, 1L, null, null, null, "ACTIVE", null, null);
	}

	private static final CapabilityCatalogProvider CATALOGS = () -> CATALOG;

	private static AskService service(Proposal proposal, AccountMatch match) {
		return new AskService(
				(question, catalog, today) -> proposal,
				(accountExternalId, capabilityKey, asAt) -> CHECK_PAYLOAD,
				mention -> match,
				CATALOGS,
				FIXED_CLOCK);
	}

	@Test
	void answersWhenAccountAndCapabilityBothResolve() {
		AskResponse response = service(
				new Proposal("Acme Corp", List.of("export.parquet"), "parquet"),
				new AccountMatch.One(account("acme", "Acme Corp")))
				.ask("Can Acme Corp export parquet?");

		assertThat(response.status()).isEqualTo(AskResponse.ANSWERED);
		assertThat(response.interpretation().account().external()).isEqualTo("acme");
		assertThat(response.interpretation().capability()).isEqualTo("export.parquet");
		assertThat(response.result()).isSameAs(CHECK_PAYLOAD);
	}

	@Test
	void dropsKeysTheRegistryDoesNotKnow() {
		// Criterion 10: a conjured key must never be answered.
		AskResponse response = service(
				new Proposal("Acme Corp", List.of("made.up", "export.parquet"), null),
				new AccountMatch.One(account("acme", "Acme Corp")))
				.ask("q");

		assertThat(response.status()).isEqualTo(AskResponse.ANSWERED);
		assertThat(response.interpretation().capability()).isEqualTo("export.parquet");
	}

	@Test
	void listsAccountCandidatesForAmbiguousMentions() {
		AskResponse response = service(
				new Proposal("Acme", List.of("export.parquet"), "parquet"),
				new AccountMatch.Candidates(List.of(
						account("acme", "Acme Corp"), account("acme-emea", "Acme EMEA Ltd"))))
				.ask("q");

		assertThat(response.status()).isEqualTo(AskResponse.CLARIFY);
		assertThat(response.accountCandidates()).extracting(AskResponse.AccountRef::external)
				.containsExactly("acme", "acme-emea");
		assertThat(response.interpretation().capability()).isEqualTo("export.parquet");
		assertThat(response.result()).isNull();
	}

	@Test
	void listsCapabilityCandidatesWhenSeveralKeysSurvive() {
		AskResponse response = service(
				new Proposal("Acme Corp", List.of("export.parquet", "export.pdf"), "export"),
				new AccountMatch.One(account("acme", "Acme Corp")))
				.ask("q");

		assertThat(response.status()).isEqualTo(AskResponse.CLARIFY);
		assertThat(response.capabilityCandidates()).containsExactly("export.parquet", "export.pdf");
		assertThat(response.result()).isNull();
	}

	@Test
	void missingAccountMentionAsksForTheAccount() {
		AskResponse response = service(
				new Proposal(null, List.of("export.parquet"), "parquet"),
				new AccountMatch.None())
				.ask("Can they export parquet?");

		assertThat(response.status()).isEqualTo(AskResponse.NO_MATCH);
		assertThat(response.detail()).isEqualTo("Tell me which account you mean.");
	}

	@Test
	void unmatchedAccountSaysWhichPartFailed() {
		AskResponse response = service(
				new Proposal("Acme Ltd", List.of("export.parquet"), null),
				new AccountMatch.None())
				.ask("q");

		assertThat(response.status()).isEqualTo(AskResponse.NO_MATCH);
		assertThat(response.unmatched().accountMention()).isEqualTo("Acme Ltd");
		assertThat(response.detail()).contains("Acme Ltd");
	}

	@Test
	void tooManyAccountMatchesAsksToBeMoreSpecific() {
		AskResponse response = service(
				new Proposal("A", List.of("export.parquet"), null),
				new AccountMatch.TooMany())
				.ask("q");

		assertThat(response.status()).isEqualTo(AskResponse.NO_MATCH);
		assertThat(response.detail()).contains("be more specific");
	}

	@Test
	void unmatchedCapabilityEchoesTheMention() {
		AskResponse response = service(
				new Proposal("Acme Corp", List.of(), "quantum sync"),
				new AccountMatch.One(account("acme", "Acme Corp")))
				.ask("q");

		assertThat(response.status()).isEqualTo(AskResponse.NO_MATCH);
		assertThat(response.unmatched().capabilityMention()).isEqualTo("quantum sync");
	}

	@Test
	void retiredCapabilityNamedOnlyByMentionIsStatedAsRetiredNotNoMatch() {
		// Criterion 7: the model proposed no catalogue key, but the plain mention text names a
		// retired capability by its key — retirement is detected locally from the catalogue.
		AskResponse response = service(
				new Proposal("Acme Corp", List.of(), "export.csv"),
				new AccountMatch.One(account("acme", "Acme Corp")))
				.ask("Can Acme still use export.csv?");

		assertThat(response.status()).isEqualTo(AskResponse.RETIRED_CAPABILITY);
		assertThat(response.interpretation().capability()).isEqualTo("export.csv");
	}

	@Test
	void retiredCapabilityProposedByKeyIsStatedAsRetiredBeforeAnyAccountLookup() {
		// The catalogue carries every status, so a proposed retired key survives the containsKey
		// filter; retirement must still short-circuit before the checker or the account matcher run.
		AskResponse response = service(
				new Proposal(null, List.of("export.csv"), "csv export"),
				new AccountMatch.None())
				.ask("Can Acme still use CSV export?");

		assertThat(response.status()).isEqualTo(AskResponse.RETIRED_CAPABILITY);
		assertThat(response.interpretation().capability()).isEqualTo("export.csv");
	}

	@Test
	void unconfiguredServiceThrowsAskUnavailable() {
		AskService service = new AskService(null, null, mention -> new AccountMatch.None(), CATALOGS, FIXED_CLOCK);

		assertThat(service.available()).isFalse();
		assertThatExceptionOfType(AskUnavailableException.class)
				.isThrownBy(() -> service.ask("q"));
	}
}
