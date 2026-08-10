package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.error.GlobalExceptionHandler;
import com.solovis.entitlement.service.store.DecisionReadDao;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AskControllerTest {

	private static final CapabilityCatalogProvider EMPTY_CATALOGS = new CapabilityCatalogProvider() {
		@Override
		public CapabilityCatalog current() {
			return new CapabilityCatalog(List.of());
		}

		@Override
		public Optional<String> retiredMatch(List<String> mentions) {
			return Optional.empty();
		}
	};

	private MockMvc mockMvcWithUnconfiguredService() {
		AskService unconfigured = new AskService(null, null,
				mention -> new AccountMatch.None(), EMPTY_CATALOGS);
		// The handler's constructor takes DecisionReadDao only for the /v1/ snapshot-version
		// header; /admin/v1/check/ask is not one of those paths, so an unstubbed mock is enough.
		return MockMvcBuilders.standaloneSetup(new AskController(unconfigured))
				.setControllerAdvice(new GlobalExceptionHandler(mock(DecisionReadDao.class)))
				.build();
	}

	@Test
	void answers503ProblemWhenTheFeatureIsOff() throws Exception {
		// Criterion 12: unconfigured ⇒ a plain "unavailable", never an error page or a guess.
		mockMvcWithUnconfiguredService()
				.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"Can Acme export parquet?\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value("entitlement/ask-unavailable"));
	}

	@Test
	void rejectsABlankQuestion() throws Exception {
		mockMvcWithUnconfiguredService()
				.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
	}

	@Test
	void rejectsAQuestionOverFiveHundredCharacters() throws Exception {
		String tooLong = "a".repeat(501);
		mockMvcWithUnconfiguredService()
				.perform(post("/admin/v1/check/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\": \"" + tooLong + "\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
	}
}
