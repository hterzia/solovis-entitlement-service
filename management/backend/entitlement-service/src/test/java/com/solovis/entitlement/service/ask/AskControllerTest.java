package com.solovis.entitlement.service.ask;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

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
		return MockMvcBuilders.standaloneSetup(new AskController(unconfigured)).build();
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
				.andExpect(status().isBadRequest());
	}
}
