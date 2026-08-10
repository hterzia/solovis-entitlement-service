package com.solovis.entitlement.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AskProperties.class)
class AskConfiguration {

	/** Only exists when an api-key is configured — the bean's absence is the feature flag. */
	@Bean
	@ConditionalOnExpression("!'${entitlement.ask.api-key:}'.isBlank()")
	QuestionInterpreter questionInterpreter(AskProperties properties, ObjectMapper objectMapper) {
		ChatModel model = GoogleAiGeminiChatModel.builder()
				.apiKey(properties.apiKey())
				.modelName(properties.model())
				.temperature(0.0)
				.timeout(properties.timeout())
				.build();
		return new GeminiQuestionInterpreter(model, objectMapper);
	}

	/**
	 * Always present so the controller can answer an honest 503. It becomes functional when both
	 * optional collaborators exist: the interpreter (needs the api-key) and the checker port
	 * (needs the api-layer worktree to merge).
	 */
	@Bean
	AskService askService(ObjectProvider<QuestionInterpreter> interpreter,
			ObjectProvider<CheckerPort> checker,
			AccountMatcher accountMatcher,
			CapabilityCatalogProvider catalogs) {
		return new AskService(interpreter.getIfAvailable(), checker.getIfAvailable(),
				accountMatcher, catalogs);
	}
}
