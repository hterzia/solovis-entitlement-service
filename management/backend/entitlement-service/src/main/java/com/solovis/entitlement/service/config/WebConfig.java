package com.solovis.entitlement.service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Forwards any GET that isn't /v1, /admin/v1 or /actuator to the SPA entry point — one deployable,
 * no CORS (plan.md, "entitlement-ui"). The pattern must also exclude index.html itself: without
 * that exclusion, forwarding to /index.html re-matches this same view controller and forwards to
 * itself forever, ending in a StackOverflowError (observed as a 500 on every non-API path).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:^(?!v1|admin|actuator|swagger-ui|v3|index\\.html).*$}").setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!v1|admin|actuator|swagger-ui|v3|index\\.html).*$}/**").setViewName("forward:/index.html");
    }
}
