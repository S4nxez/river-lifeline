package com.nisarsar.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nisarsar.dao.repositories.CredentialRepository;
import com.nisarsar.domain.services.CustomUserDetailsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

class WebConfigTest {
    private final WebConfig config = new WebConfig();
    private final PasswordEncoder encoder = config.encoder();

    @Test
    void passwordsAreHashedWithBcrypt() {
        String hash = encoder.encode("s3cret");

        assertThat(hash).startsWith("$2").isNotEqualTo("s3cret");
        assertThat(encoder.matches("s3cret", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }

    @Test
    void usersAreLoadedFromTheCredentialRepository() {
        assertThat(config.userDetailsService(mock(CredentialRepository.class)))
                .isInstanceOf(CustomUserDetailsService.class);
    }

    @Test
    void theAuthenticationProviderAcceptsTheRightPassword() {
        AuthenticationProvider provider = providerFor("alice", "s3cret");

        assertThat(provider.authenticate(new UsernamePasswordAuthenticationToken("alice", "s3cret"))
                        .isAuthenticated())
                .isTrue();
    }

    @Test
    void theAuthenticationProviderRejectsAWrongPassword() {
        AuthenticationProvider provider = providerFor("alice", "s3cret");

        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken("alice", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    private AuthenticationProvider providerFor(String username, String password) {
        UserDetailsService users = mock(UserDetailsService.class);
        when(users.loadUserByUsername(username))
                .thenReturn(User.withUsername(username)
                        .password(encoder.encode(password))
                        .authorities(List.of())
                        .build());
        return config.authenticationProvider(users, encoder);
    }
}
