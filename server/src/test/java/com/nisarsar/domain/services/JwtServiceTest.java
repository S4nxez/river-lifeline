package com.nisarsar.domain.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class JwtServiceTest {
    private static final long ONE_MINUTE = 60_000;

    private final UserDetails alice = user("alice");
    private final JwtService service = jwtService("test-secret", ONE_MINUTE);

    @Test
    void aGeneratedTokenCarriesTheUsername() {
        assertThat(service.extractUsername(service.generateToken(alice))).isEqualTo("alice");
    }

    @Test
    void aRefreshTokenCarriesTheUsername() {
        assertThat(service.extractUsername(service.generateRefreshToken(alice))).isEqualTo("alice");
    }

    @Test
    void aTokenIsValidOnlyForItsOwner() {
        String token = service.generateToken(alice);

        assertThat(service.isTokenValid(token, alice)).isTrue();
        assertThat(service.isTokenValid(token, user("bob"))).isFalse();
    }

    @Test
    void anExpiredTokenIsRejectedAsUnauthorized() {
        String expired = jwtService("test-secret", -ONE_MINUTE).generateToken(alice);

        assertThatThrownBy(() -> service.extractUsername(expired))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String forged = jwtService("another-secret", ONE_MINUTE).generateToken(alice);

        assertThatThrownBy(() -> service.extractUsername(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void extractAuthoritiesTurnsEachRoleIntoARoleAuthority() {
        String token =
                service.generateToken(Map.of("auth", List.of(Map.of("role", "ADMIN"), Map.of("role", "USER"))), alice);

        assertThat(service.extractAuthorities(token))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void extractAuthoritiesRejectsATokenWithoutRoles() {
        String token = service.generateRefreshToken(alice);

        assertThatThrownBy(() -> service.extractAuthorities(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The token has no valid authorities");
    }

    private static JwtService jwtService(String secret, long expirationMillis) {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", secret);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", expirationMillis);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", expirationMillis);
        return jwtService;
    }

    private static UserDetails user(String username) {
        return User.withUsername(username)
                .password("unused")
                .authorities(List.of())
                .build();
    }
}
