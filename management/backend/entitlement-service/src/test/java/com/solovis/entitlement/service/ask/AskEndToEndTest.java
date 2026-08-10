package com.solovis.entitlement.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.AccountRow;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.CapabilityRow;
import com.solovis.entitlement.service.store.PlanRepository;
import com.solovis.entitlement.service.store.PlanRow;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two MockMvc proofs the plan calls for: that {@code $.result} is byte-for-byte what
 * {@code GET /admin/v1/check} itself returns (criteria 1, 3), and that asking never writes an
 * audit row regardless of which of the four statuses it lands on (criterion 11).
 *
 * <p>Not {@code @Transactional}, matching {@code DecisionControllerTest}: the checker reads
 * through the read pool, which only sees committed rows. Fixture keys are namespaced ("t5") so
 * this class's permanent writes don't collide with another non-transactional class sharing this
 * JVM fork's SQLite file.
 *
 * <p>Interpretation is stubbed via a {@code @Primary} {@link QuestionInterpreter} bean — no
 * Gemini key is present in the test environment, so this is the only way to drive every branch
 * deterministically.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AskEndToEndTest {

	@Autowired MockMvc mockMvc;
	@Autowired CapabilityRepository capabilityRepository;
	@Autowired PlanRepository planRepository;
	@Autowired AccountRepository accountRepository;
	@Autowired AuditEventRepository auditEventRepository;
	@Autowired StubInterpreter stubInterpreter;
	@Autowired java.time.Clock clock;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String ACCOUNT_EXTERNAL = "acct_ask_t5";
	private static final String ACCOUNT_MENTION = "Ask T5 Corp";
	private static final String CAPABILITY_KEY = "reports.ask_t5.monthly";
	private static final String OTHER_CAPABILITY_KEY = "reports.ask_t5.weekly";
	private static final String RETIRED_CAPABILITY_KEY = "reports.ask_t5.retired";

	@TestConfiguration
	static class AskStubConfig {
		@Bean
		@Primary
		StubInterpreter stubInterpreter() {
			return new StubInterpreter();
		}
	}

	/** A mutable stand-in the test drives directly — no model call, no network. */
	static class StubInterpreter implements QuestionInterpreter {
		volatile Proposal next;

		@Override
		public Proposal interpret(String question, CapabilityCatalog catalog, LocalDate today) {
			return next;
		}
	}

	@BeforeAll
	void seed() {
		capabilityRepository.insert(new CapabilityRow(null, CAPABILITY_KEY, "reports", "Monthly reports", null,
				"QUANTITY", null, 50L, false, null, false, null, null, "ACTIVE", null,
				"2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
		capabilityRepository.insert(new CapabilityRow(null, OTHER_CAPABILITY_KEY, "reports", "Weekly reports", null,
				"QUANTITY", null, 10L, false, null, false, null, null, "ACTIVE", null,
				"2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
		long retiredId = capabilityRepository.insert(new CapabilityRow(null, RETIRED_CAPABILITY_KEY, "reports",
				"Retired reports", null, "QUANTITY", null, 10L, false, null, false, null, null, "ACTIVE", null,
				"2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
		capabilityRepository.retire(retiredId, "2026-08-10T01:00:00.000Z", "2026-08-10T01:00:00.000Z");

		long planId = planRepository.insert(new PlanRow(null, "t5-plan", "T5 Plan", null, "ACTIVE", false,
				"2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
		accountRepository.insert(new AccountRow(null, ACCOUNT_EXTERNAL, ACCOUNT_MENTION, planId,
				"2026-08-10T00:00:00.000Z", "PERSON", "dev-operator", "ACTIVE",
				"2026-08-10T00:00:00.000Z", "2026-08-10T00:00:00.000Z"));
	}

	private JsonNode askResult(String question) throws Exception {
		String body = mockMvc.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"" + question + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ANSWERED"))
				.andReturn().getResponse().getContentAsString();
		return MAPPER.readTree(body).get("result");
	}

	@Test
	void answeredResultIsByteForByteTheClassicCheckerPayload() throws Exception {
		stubInterpreter.next = new Proposal(ACCOUNT_MENTION, List.of(CAPABILITY_KEY), null);

		JsonNode askResult = askResult("Can Ask T5 Corp get monthly reports?");

		String checkBody = mockMvc.perform(get("/admin/v1/check")
						.param("account", ACCOUNT_EXTERNAL).param("capability", CAPABILITY_KEY))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode checkResult = MAPPER.readTree(checkBody);

		// evaluatedAt is real wall-clock time, computed independently by each call — the two
		// requests land a few milliseconds apart, so it is the one field expected to differ.
		((ObjectNode) askResult).remove("evaluatedAt");
		((ObjectNode) checkResult).remove("evaluatedAt");
		assertThat(askResult).isEqualTo(checkResult);
	}

	@Test
	void auditIsSilentAcrossEveryAskStatus() throws Exception {
		long before = auditEventRepository.findMaxSeq().orElse(0L);

		// ANSWERED
		stubInterpreter.next = new Proposal(ACCOUNT_MENTION, List.of(CAPABILITY_KEY), null);
		mockMvc.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"Can Ask T5 Corp get monthly reports?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ANSWERED"));

		// CLARIFY — two capability keys survive
		stubInterpreter.next = new Proposal(ACCOUNT_MENTION, List.of(CAPABILITY_KEY, OTHER_CAPABILITY_KEY), "reports");
		mockMvc.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"Can Ask T5 Corp get reports?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLARIFY"));

		// NO_MATCH — no account named at all
		stubInterpreter.next = new Proposal(null, List.of(CAPABILITY_KEY), null);
		mockMvc.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"Can they get monthly reports?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("NO_MATCH"));

		// RETIRED_CAPABILITY
		stubInterpreter.next = new Proposal(ACCOUNT_MENTION, List.of(RETIRED_CAPABILITY_KEY), null);
		mockMvc.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"Can Ask T5 Corp still get retired reports?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RETIRED_CAPABILITY"));

		long after = auditEventRepository.findMaxSeq().orElse(0L);
		assertThat(after).as("asking must never write an audit row (criterion 11)").isEqualTo(before);
	}

	/**
	 * 002's three date refusals are delegated, never reimplemented (§5) — the ask path does no
	 * local check beyond parseability, so a well-formed but unanswerable day must reach the
	 * checker's own point-in-time route and come back as that route's own error, untouched.
	 */
	@Test
	void aFutureResolvedDatePassesThroughAs002sOwnRefusal() throws Exception {
		String tomorrow = LocalDate.now(clock).plusDays(1).toString();
		stubInterpreter.next = new Proposal(ACCOUNT_MENTION, List.of(CAPABILITY_KEY), null, "tomorrow", tomorrow);

		mockMvc.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"Could Ask T5 Corp get monthly reports tomorrow?\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.type").value("entitlement/future-date"));
	}

	@Test
	void aDateBeforeTheAccountExistedPassesThroughAs002sOwnRefusal() throws Exception {
		// The account was seeded at 2026-08-10T00:00:00Z; well before that has no answer. Which of
		// 002's two "no answer in the past" refusals fires depends on where the change history
		// itself starts, not something this ask-path test seeds — AsAtCheckTest accepts either for
		// the same reason. Either way it must be one of 002's own types, never a denial or a guess.
		stubInterpreter.next = new Proposal(ACCOUNT_MENTION, List.of(CAPABILITY_KEY), null, "in 2020", "2020-01-01");

		mockMvc.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"Could Ask T5 Corp get monthly reports back in 2020?\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.in(
						List.of("entitlement/before-account-existed", "entitlement/beyond-history"))));
	}
}
