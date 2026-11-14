package com.riverlifeline.monitoring.infrastructure.adapter.in.web.filter;

import com.riverlifeline.monitoring.application.service.ApiVersion;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every API response, errors included, announces the contract version: {@code X-Schema-Version: 1.0.0}. */
public class SchemaVersionHeaderFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(ApiPaths.SCHEMA_VERSION_HEADER, ApiVersion.SCHEMA);
        chain.doFilter(request, response);
    }
}
