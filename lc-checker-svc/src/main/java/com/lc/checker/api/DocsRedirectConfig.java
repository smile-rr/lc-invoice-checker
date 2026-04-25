package com.lc.checker.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Root-URL convenience routing for the Scalar API reference page.
 *
 * <ul>
 *   <li>{@code GET /}      → forward to {@code /docs.html}</li>
 *   <li>{@code GET /docs}  → forward to {@code /docs.html}</li>
 * </ul>
 *
 * <p>{@code /docs.html} itself is a static resource under
 * {@code src/main/resources/static/} served by Spring Boot's default
 * {@code ResourceHttpRequestHandler}.
 */
@Configuration
public class DocsRedirectConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/docs.html");
        registry.addViewController("/docs").setViewName("forward:/docs.html");
    }
}
