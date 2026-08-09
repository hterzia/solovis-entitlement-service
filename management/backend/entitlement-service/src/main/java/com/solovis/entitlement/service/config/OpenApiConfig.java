package com.solovis.entitlement.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI entitlementServiceOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Entitlement Service")
            .description("Capability registry, plans, GRANT/HOLD overrides, and trace-explained decisions. "
                + "See .specs/001-entitlement-service/contracts/ for the source-of-truth contracts this API implements.")
            .version("v1"));
    }
}
