package com.nisarsar.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nisarsar.domain.services.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class TokenFilterTest {
    @Mock
    private JwtService jwtService;

    @Mock
    private FilterChain chain;

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void thePublicSitesApiSkipsTokenChecks() throws Exception {
        MockHttpServletRequest request = request("/api/sites/ES_VALENCIA_001", "Bearer token");

        filter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService);
        assertThat(authentication()).isNull();
    }

    @Test
    void aRequestWithoutABearerTokenStaysAnonymous() throws Exception {
        MockHttpServletRequest request = request("/api/private", "Basic dXNlcjpwYXNz");

        filter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService);
        assertThat(authentication()).isNull();
    }

    @Test
    void aValidBearerTokenAuthenticatesItsUser() throws Exception {
        when(jwtService.extractUsername("token")).thenReturn("alice");
        when(jwtService.isTokenValid(eq("token"), any())).thenReturn(true);
        MockHttpServletRequest request = request("/api/private", "Bearer token");

        filter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(authentication()).isNotNull();
        assertThat(authentication().getName()).isEqualTo("alice");
    }

    @Test
    void anUnreadableTokenStaysAnonymous() throws Exception {
        when(jwtService.extractUsername("garbage")).thenThrow(new IllegalArgumentException("malformed"));
        MockHttpServletRequest request = request("/api/private", "Bearer garbage");

        filter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(authentication()).isNull();
    }

    @Test
    void aTokenThatFailsValidationStaysAnonymous() throws Exception {
        when(jwtService.extractUsername("token")).thenReturn("alice");
        when(jwtService.isTokenValid(eq("token"), any())).thenReturn(false);
        MockHttpServletRequest request = request("/api/private", "Bearer token");

        filter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(authentication()).isNull();
    }

    @Test
    void aValidationErrorStaysAnonymous() throws Exception {
        when(jwtService.extractUsername("token")).thenReturn("alice");
        when(jwtService.isTokenValid(eq("token"), any())).thenThrow(new IllegalStateException("expired"));
        MockHttpServletRequest request = request("/api/private", "Bearer token");

        filter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(authentication()).isNull();
    }

    private TokenFilter filter() {
        return new TokenFilter(jwtService);
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.addHeader("Authorization", authorization);
        return request;
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
