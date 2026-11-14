package com.riverlifeline.monitoring.infrastructure.config;

import com.riverlifeline.monitoring.infrastructure.adapter.in.web.ApiPaths;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.filter.CatalogEtagFilter;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.filter.SchemaVersionHeaderFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the front origins (GET only) and the API's response headers. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private static final String API_PATTERN = ApiPaths.BASE + "/*";

    private final RiverProperties properties;

    public WebConfiguration(RiverProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (properties.corsOrigins().isEmpty()) {
            return;
        }
        registry.addMapping(ApiPaths.BASE + "/**")
                .allowedOrigins(properties.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .exposedHeaders(HttpHeaders.ETAG, ApiPaths.SCHEMA_VERSION_HEADER, HttpHeaders.CONTENT_DISPOSITION)
                .maxAge(3600);
    }

    @Bean
    FilterRegistrationBean<SchemaVersionHeaderFilter> schemaVersionHeaderFilter() {
        var registration = new FilterRegistrationBean<>(new SchemaVersionHeaderFilter());
        registration.addUrlPatterns(API_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<CatalogEtagFilter> catalogEtagFilter() {
        var registration = new FilterRegistrationBean<>(new CatalogEtagFilter());
        registration.addUrlPatterns(API_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
