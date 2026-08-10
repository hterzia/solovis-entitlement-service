package com.solovis.entitlement.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live call to the Gemini Developer API — runs only when the key is present in the environment
 * (source the repository-root .env first), so CI without a key stays green by skipping.
 */
@EnabledIfEnvironmentVariable(named = "GOOGLE_AI_GEMINI_API_KEY", matches = ".+")
class GeminiQuestionInterpreterSmokeTest {

	private static final CapabilityCatalog CATALOG = new CapabilityCatalog(List.of(
			new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export"),
			new CapabilityCatalog.Entry("export.pdf", "export", "PDF export"),
			new CapabilityCatalog.Entry("api.access", "api", "API access"),
			new CapabilityCatalog.Entry("reports.monthly", "reports", "Monthly reports")));

	private QuestionInterpreter interpreter() {
		ChatModel model = GoogleAiGeminiChatModel.builder()
				.apiKey(System.getenv("GOOGLE_AI_GEMINI_API_KEY"))
				.modelName("gemini-3.5-flash-lite")
				.temperature(0.0)
				.timeout(Duration.ofSeconds(10))
				.build();
		return new GeminiQuestionInterpreter(model, new ObjectMapper());
	}

	@Test
	void extractsAccountAndCapabilityFromACanonicalQuestion() {
		Proposal proposal = interpreter().interpret("Can Acme Corp export parquet?", CATALOG);

		assertThat(proposal.accountMention()).containsIgnoringCase("acme");
		assertThat(proposal.capabilityKeys()).contains("export.parquet");
	}

	@Test
	void reportsNoCapabilityWhenNothingInTheCatalogueFits() {
		Proposal proposal = interpreter().interpret("Can Acme Corp use quantum synchronisation?", CATALOG);

		assertThat(proposal.capabilityKeys()).isEmpty();
	}
}
