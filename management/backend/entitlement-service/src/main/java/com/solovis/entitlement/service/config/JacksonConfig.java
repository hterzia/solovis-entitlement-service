package com.solovis.entitlement.service.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Spring Boot 4's default JSON stack is Jackson 3 (tools.jackson), configured via a
 * JsonMapper.Builder rather than the deprecated Jackson2ObjectMapperBuilderCustomizer. This
 * enforces non-null inclusion and ISO-8601-with-millis Instants in code, since src/test/resources
 * shadows src/main/resources/application.yaml wholesale and never sees the `spring.jackson.*` keys there.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer entitlementJsonMapperBuilderCustomizer() {
        return builder -> builder
            .changeDefaultPropertyInclusion(incl -> incl
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.NON_NULL))
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    }
}
