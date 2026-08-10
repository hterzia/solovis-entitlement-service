package com.solovis.entitlement.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;

/**
 * Gemini-backed interpreter (structured-JSON call, patterned on the reference repo's
 * {@code Example05_ChatWithJsonResponse}). Holds the {@link ChatModel} and nothing else —
 * no repository, snapshot or resolver reference can exist here, which is the code half of
 * spec criterion 9.
 */
public final class GeminiQuestionInterpreter implements QuestionInterpreter {

	static final String TASK_PROMPT = """
			You read one operator question about a customer entitlement system.
			Extract which account and which capability the question asks about.
			Reply as JSON with:
			- accountMention: the exact words the operator used to name the account, or null if the question names none
			- capabilityKeys: 0 to 3 keys from the catalogue below that plausibly match what is asked about, best match first
			- capabilityMention: the words the operator used for the capability, or null
			Rules: only ever use keys that appear in the catalogue; never invent an account; when nothing in the catalogue fits, return an empty capabilityKeys list.

			Capability catalogue:
			""";

	private static final ResponseFormat PROPOSAL_FORMAT = ResponseFormat.builder()
			.type(ResponseFormat.JSON.type())
			.jsonSchema(JsonSchema.builder()
					.name("proposal")
					.rootElement(JsonObjectSchema.builder()
							.addStringProperty("accountMention")
							.addProperty("capabilityKeys", JsonArraySchema.builder()
									.items(new JsonStringSchema())
									.build())
							.addStringProperty("capabilityMention")
							.required("capabilityKeys")
							.build())
					.build())
			.build();

	private final ChatModel model;
	private final ObjectMapper objectMapper;

	public GeminiQuestionInterpreter(ChatModel model, ObjectMapper objectMapper) {
		this.model = model;
		this.objectMapper = objectMapper;
	}

	@Override
	public Proposal interpret(String question, CapabilityCatalog catalog) {
		ChatRequest request = ChatRequest.builder()
				.messages(List.of(
						SystemMessage.from(TASK_PROMPT + catalog.render()),
						UserMessage.from(question)))
				.responseFormat(PROPOSAL_FORMAT)
				.build();
		try {
			String json = model.chat(request).aiMessage().text();
			return objectMapper.readValue(json, Proposal.class);
		}
		catch (Exception e) {
			throw new AskUnavailableException("Question interpretation failed", e);
		}
	}
}
