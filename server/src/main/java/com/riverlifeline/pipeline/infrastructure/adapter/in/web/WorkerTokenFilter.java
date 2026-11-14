package com.riverlifeline.pipeline.infrastructure.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a worker request through only with the shared secret in {@code X-Worker-Token} (compared in constant time).
 * Without a configured secret ({@code WORKER_TOKEN}) every worker request is refused.
 */
public class WorkerTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Worker-Token";
    private static final Logger log = LoggerFactory.getLogger(WorkerTokenFilter.class);

    private final byte[] expected;

    public WorkerTokenFilter(String token) {
        this.expected = token == null || token.isBlank() ? null : token.strip().getBytes(StandardCharsets.UTF_8);
        if (expected == null) {
            log.warn("WORKER_TOKEN is not set: the internal worker API refuses every request");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(WorkerApiController.BASE + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (expected == null
                || given == null
                || !MessageDigest.isEqual(expected, given.strip().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or wrong " + HEADER + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
