package com.solovis.entitlement.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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
	 * Always present so the controller can answer an honest 503. The checker port is a real bean
	 * unconditionally; only the interpreter is optional, since an absent api-key is the whole
	 * feature flag (§ available()).
	 */
	@Bean
	AskService askService(ObjectProvider<QuestionInterpreter> interpreter,
			CheckerPort checker,
			AccountMatcher accountMatcher,
			CapabilityCatalogProvider catalogs,
			Clock clock) {
		return new AskService(interpreter.getIfAvailable(), checker, accountMatcher, catalogs, clock);
	}
}
