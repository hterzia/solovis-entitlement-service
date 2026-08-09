package com.solovis.entitlement.service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Forwards any GET that isn't /v1, /admin/v1 or /actuator to the SPA entry point — one deployable,
 * no CORS (plan.md, "entitlement-ui").
 *
 * <p>Two exclusions are load-bearing, and both fail in ways that look like something else:
 *
 * <ul>
 *   <li><b>{@code assets}</b> — view controllers are ordered ahead of Spring's static resource
 *       handler, so without this the bundle itself is forwarded to index.html. It returns 200 with
 *       {@code text/html}, the browser rejects it under strict MIME checking, and the whole UI
 *       renders blank with no failed request to point at.
 *   <li><b>anything containing a dot</b> ({@code [^.]*}) — covers index.html and favicon.svg.
 *       Forwarding index.html re-matches this controller and forwards to itself forever, ending in
 *       a StackOverflowError seen as a 500 on every non-API path.
 * </ul>
 *
 * <p>The dot exclusion applies only to the <em>first</em> path segment, so dotted capability keys
 * in deeper segments still reach the SPA: {@code /capabilities/seats.count} forwards correctly.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String SPA_SEGMENT = "/{path:^(?!v1|admin|actuator|swagger-ui|v3|assets)[^.]*$}";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController(SPA_SEGMENT).setViewName("forward:/index.html");
        registry.addViewController(SPA_SEGMENT + "/**").setViewName("forward:/index.html");
    }
}
