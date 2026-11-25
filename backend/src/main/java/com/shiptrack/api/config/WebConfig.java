package com.shiptrack.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Wires the two auth realms: {@code /api/v1/**} uses the Bearer token,
 * {@code /internal/**} uses {@code X-Internal-Token} and is never public.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final InternalAuthInterceptor internalAuthInterceptor;
    private final ApiProperties properties;

    public WebConfig(AuthInterceptor authInterceptor, InternalAuthInterceptor internalAuthInterceptor,
                     ApiProperties properties) {
        this.authInterceptor = authInterceptor;
        this.internalAuthInterceptor = internalAuthInterceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/health");
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = properties.getCorsAllowedOrigins();
        if (origins == null || origins.isBlank()) {
            return;
        }
        String[] allowed = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
        if (allowed.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowed)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept")
                .maxAge(600);
    }
}
