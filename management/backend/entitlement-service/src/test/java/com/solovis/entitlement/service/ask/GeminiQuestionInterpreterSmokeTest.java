package com.solovis.entitlement.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live call to the Gemini Developer API — runs only when the key is present in the environment
 * (source the repository-root .env first), so CI without a key stays green by skipping.
 */
@EnabledIfEnvironmentVariable(named = "GOOGLE_AI_GEMINI_API_KEY", matches = ".+")
class GeminiQuestionInterpreterSmokeTest {

	private static final Logger log = LoggerFactory.getLogger(GeminiQuestionInterpreterSmokeTest.class);

	private static final CapabilityCatalog CATALOG = new CapabilityCatalog(List.of(
			new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export", false),
			new CapabilityCatalog.Entry("export.pdf", "export", "PDF export", false),
			new CapabilityCatalog.Entry("api.access", "api", "API access", false),
			new CapabilityCatalog.Entry("reports.monthly", "reports", "Monthly reports", false)));

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

	private QuestionInterpreter interpreter() {
		return new GeminiQuestionInterpreter(model(List.of()), new ObjectMapper());
	}

	private ChatModel model(List<ChatModelListener> listeners) {
		return GoogleAiGeminiChatModel.builder()
				.apiKey(System.getenv("GOOGLE_AI_GEMINI_API_KEY"))
				.modelName("gemini-3.5-flash-lite")
				.temperature(0.0)
				.timeout(Duration.ofSeconds(10))
				.listeners(listeners)
				.build();
	}

	@Test
	void extractsAccountAndCapabilityFromACanonicalQuestion() {
		Proposal proposal = interpreter().interpret("Can Acme Corp export parquet?", CATALOG, TODAY);

		assertThat(proposal.accountMention()).containsIgnoringCase("acme");
		assertThat(proposal.capabilityKeys()).contains("export.parquet");
	}

	@Test
	void reportsNoCapabilityWhenNothingInTheCatalogueFits() {
		Proposal proposal = interpreter().interpret("Can Acme Corp use quantum synchronisation?", CATALOG, TODAY);

		assertThat(proposal.capabilityKeys()).isEmpty();
	}

	@Test
	void aQuestionNamingNoTimeReferenceProposesNoDate() {
		Proposal proposal = interpreter().interpret("Can Acme Corp export parquet?", CATALOG, TODAY);

		// The raw interpreter is asserted here, before AskService's own normalization — and the
		// live model was observed, run to run, representing "absent" three different ways: JSON
		// null, an empty string, and the four-character literal "null". All three are legitimate
		// model output for this same field; AskService.blankToNull is where "absent" is decided for
		// real (c16), which is exactly why this assertion accepts all three rather than one.
		assertThat(isAbsent(proposal.dateMention())).as("dateMention: %s", proposal.dateMention()).isTrue();
		assertThat(isAbsent(proposal.resolvedDate())).as("resolvedDate: %s", proposal.resolvedDate()).isTrue();
	}

	private static boolean isAbsent(String value) {
		return value == null || value.isBlank() || value.trim().equalsIgnoreCase("null");
	}

	@Test
	void aQuestionNamingATimeReferenceProposesADateMention() {
		// The words themselves are what the model returns; whether it also pins down a specific
		// day (both set) or treats the phrase as too vague (mention only) is model behaviour this
		// test does not over-assert — the local parse in AskService is what draws that line for
		// real, against a model output that varies. dateMention is the one thing always present.
		for (String question : List.of(
				"How many reports could Acme export last month?",
				"Could Acme export parquet on 14 March?",
				"Did Acme export parquet recently?")) {
			Proposal proposal = interpreter().interpret(question, CATALOG, TODAY);
			assertThat(proposal.dateMention()).as("question: %s", question).isNotNull();
		}
	}

	/**
	 * c9's only mechanical proof: the outbound request must carry exactly the question, the
	 * catalogue (a marker key stands in for it) and today's date — and nothing that identifies a
	 * customer, an account, a plan, an override or a value. A {@link ChatModelListener} is the one
	 * seam that sees the request as it actually left the process, rather than as this test assumes
	 * it was built.
	 */
	@Test
	void wireLevelConfinementCarriesOnlyTheQuestionCatalogueAndToday() {
		List<String> capturedRequestBodies = new CopyOnWriteArrayList<>();
		ChatModelListener capturing = new ChatModelListener() {
			@Override
			public void onRequest(ChatModelRequestContext context) {
				StringBuilder body = new StringBuilder();
				for (ChatMessage message : context.chatRequest().messages()) {
					body.append(message.toString()).append('\n');
				}
				capturedRequestBodies.add(body.toString());
			}
		};

		CapabilityCatalog markerCatalog = new CapabilityCatalog(List.of(
				new CapabilityCatalog.Entry("reports.confinement_marker_t12", "reports", "Confinement marker", false)));
		QuestionInterpreter interpreter = new GeminiQuestionInterpreter(model(List.of(capturing)), new ObjectMapper());

		interpreter.interpret("Can Acme Corp get the confinement marker capability?", markerCatalog, TODAY);

		assertThat(capturedRequestBodies).hasSize(1);
		String body = capturedRequestBodies.getFirst();

		assertThat(body)
				.as("the question itself must reach the model")
				.contains("Can Acme Corp get the confinement marker capability?");
		assertThat(body)
				.as("the catalogue key must reach the model")
				.contains("reports.confinement_marker_t12");
		assertThat(body)
				.as("today's date must reach the model")
				.contains(TODAY.toString());

		// None of these ever exist in a Proposal/CapabilityCatalog, so a bug that started passing
		// them would have to invent a new leak, not just fail to strip an existing field — this is
		// a floor, not a substitute for the structural confinement §4 already guarantees.
		assertThat(body).doesNotContainIgnoringCase("acct_9931");
		assertThat(body).doesNotContainIgnoringCase("northwind");
		assertThat(body).doesNotContain("ovr_");
		assertThat(body).doesNotContainIgnoringCase("GRANT");
		assertThat(body).doesNotContainIgnoringCase("suspended pending investigation");
	}

	/** c13/c21 — no harness at this volume; a handful of live calls, timed and logged. */
	@Test
	void p95OfInterpretationCallsIsWellInsideTheThreeSecondBudget() {
		List<Long> millis = new ArrayList<>();
		QuestionInterpreter interpreter = interpreter();
		for (int i = 0; i < 5; i++) {
			long start = System.nanoTime();
			interpreter.interpret("Can Acme Corp export parquet?", CATALOG, TODAY);
			millis.add((System.nanoTime() - start) / 1_000_000);
		}
		millis.sort(Long::compareTo);
		long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
		log.info("Interpretation call timings (ms): {} — p95={}ms", millis, p95);

		// This is interpretation alone (c13's own target for the whole end-to-end answer is 3s),
		// so a generous ceiling confirms the step measures well inside it without asserting a
		// number that would make this test flaky against real network variance.
		assertThat(p95).isLessThan(3_000L);
	}
}
