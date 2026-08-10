package com.solovis.entitlement.service.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

/**
 * Serves the operator SPA from the same process as the API — one deployable, no CORS
 * (plan.md, "entitlement-ui").
 *
 * <p><b>Why a resolver rather than a forwarding rule.</b> This was previously a pair of
 * {@code addViewController} rules that forwarded everything except a denylist of prefixes
 * ({@code v1|admin|actuator|swagger-ui|v3}). View controllers are ordered <i>ahead</i> of Spring's
 * static resource handler, so anything missing from that list was swallowed. {@code assets} was
 * missing, which meant the JavaScript bundle itself was forwarded to {@code index.html} and served
 * as {@code text/html}. The browser rejected it under strict MIME checking and every page rendered
 * blank — while every request returned 200, nothing 404'd, and the health check stayed green.
 *
 * <p>A denylist is the wrong shape for this: each new build output directory is another entry
 * somebody must remember, and forgetting one fails silently and totally. This resolver inverts the
 * rule so that <b>a real file always wins</b> — the bundle exists, so it is served as itself, and no
 * exclusion list needs maintaining when the front-end build output changes.
 *
 * <p>API routes never reach here at all: {@code @RequestMapping} handlers outrank the resource
 * handler, so a matched controller always takes precedence. {@link #API_PREFIXES} covers only the
 * remainder — an <i>unmatched</i> path under an API prefix, such as a typo'd endpoint, which should
 * return 404 rather than a page of HTML to a caller expecting JSON.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String SPA_ENTRY_POINT = "/static/index.html";

    /**
     * Unmatched paths beneath these must 404 rather than fall through to the SPA.
     *
     * <p>{@code assets/} is here for the same reason as the API prefixes, arriving from the other
     * direction: it is build output, never a client-side route. If a hashed bundle is missing —
     * a stale index.html pointing at a previous build, a partial deploy — returning the SPA shell
     * reproduces the exact failure this class exists to prevent, serving HTML where JavaScript is
     * expected and blanking the page with nothing to diagnose. A 404 says what actually happened.
     */
    private static final List<String> NEVER_FALL_BACK =
        List.of("v1/", "admin/", "actuator/", "v3/", "swagger-ui/", "assets/");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource getResource(String resourcePath, Resource location) throws IOException {
                    Resource requested = location.createRelative(resourcePath);
                    if (requested.exists() && requested.isReadable()) {
                        return requested;   // a real file — bundle, stylesheet, favicon
                    }
                    if (NEVER_FALL_BACK.stream().anyMatch(resourcePath::startsWith)) {
                        return null;        // missing API path or missing build output: 404, never HTML
                    }
                    return new ClassPathResource(SPA_ENTRY_POINT);   // a client-side route
                }
            });
    }
}
