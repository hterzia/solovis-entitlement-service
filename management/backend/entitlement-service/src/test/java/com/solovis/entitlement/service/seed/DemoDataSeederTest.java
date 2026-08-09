package com.solovis.entitlement.service.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DemoDataSeederTest {

    @Configuration
    static class SeedEnabledProbe {
        @Bean
        Boolean seedEnabled(@Value("${entitlement.seed.enabled:false}") boolean enabled) {
            return enabled;
        }
    }

    /**
     * A fresh production deploy with no ENTITLEMENT_SEED_ENABLED set must not seed fake demo data
     * (a named account, a live override, a fabricated justification) into a permanent audit log —
     * mirrors DemoDataSeeder's own {@code @Value} default exactly.
     */
    @Test
    void seedingDefaultsToDisabledWhenNoPropertyIsSet() {
        new ApplicationContextRunner()
            .withUserConfiguration(SeedEnabledProbe.class)
            .run(context -> assertThat(context.getBean(Boolean.class)).isFalse());
    }
}
