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

	/**
	 * Only exists when an api-key is configured — the bean's absence is the feature flag.
	 *
	 * <p>Builds its own {@link ObjectMapper} rather than injecting Spring's bean: this app's JSON
	 * stack is Jackson 3 ({@code tools.jackson}, see {@code JacksonConfig}), and a classic Jackson 2
	 * {@code ObjectMapper} bean only exists on the *test* classpath (pulled in by
	 * {@code spring-boot-starter-jackson-test} for MockMvc) — a real {@code spring-boot:run} or the
	 * packaged jar has no such bean, and this dependency would fail to autowire at actual startup
	 * despite every {@code @SpringBootTest} passing. langchain4j itself is built on classic Jackson 2
	 * (it is what pulls {@code jackson-databind} onto the compile classpath at all), so a
	 * self-constructed mapper is not a workaround — it is the correct scope for parsing a model's
	 * own JSON, independent of the app's response-serialisation tuning.
	 */
	@Bean
	@ConditionalOnExpression("!'${entitlement.ask.api-key:}'.isBlank()")
	QuestionInterpreter questionInterpreter(AskProperties properties) {
		ChatModel model = GoogleAiGeminiChatModel.builder()
				.apiKey(properties.apiKey())
				.modelName(properties.model())
				.temperature(0.0)
				.timeout(properties.timeout())
				.build();
		return new GeminiQuestionInterpreter(model, new ObjectMapper());
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
