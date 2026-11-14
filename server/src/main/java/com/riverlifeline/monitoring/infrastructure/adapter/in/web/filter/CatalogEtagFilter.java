package com.riverlifeline.monitoring.infrastructure.adapter.in.web.filter;

import com.riverlifeline.monitoring.infrastructure.adapter.in.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * {@code ETag} on the catalog-derived endpoints and {@code 304} for a matching {@code If-None-Match}. The tag is a
 * hash of the body, so it changes exactly when the answer does (a new catalog, or new communities). Health is live
 * state and the layer PNGs are streamed with their own long cache, so both are left out.
 */
public class CatalogEtagFilter extends ShallowEtagHeaderFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(ApiPaths.HEALTH) || path.contains("/layers/");
    }
}
