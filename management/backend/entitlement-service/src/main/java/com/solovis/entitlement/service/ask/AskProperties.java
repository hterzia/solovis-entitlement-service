package com.solovis.entitlement.service.ask;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * An absent or blank api-key disables the feature entirely: no interpreter bean is created and
 * {@code POST /admin/v1/check/ask} answers 503. The key is supplied via the environment
 * ({@code GOOGLE_AI_GEMINI_API_KEY}, sourced from the repository-root .env), never committed.
 */
@ConfigurationProperties(prefix = "entitlement.ask")
public record AskProperties(
		@DefaultValue("") String apiKey,
		@DefaultValue("gemini-3.5-flash-lite") String model,
		@DefaultValue("5s") Duration timeout) {

	public boolean enabled() {
		return apiKey != null && !apiKey.isBlank();
	}
}
