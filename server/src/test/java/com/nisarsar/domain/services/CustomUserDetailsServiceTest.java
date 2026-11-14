package com.nisarsar.domain.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.nisarsar.dao.model.Credential;
import com.nisarsar.dao.repositories.CredentialRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {
    @Mock
    private CredentialRepository credentials;

    @InjectMocks
    private CustomUserDetailsService service;

    @Test
    void loadsTheStoredCredentials() {
        when(credentials.findByUsername("alice")).thenReturn(Optional.of(new Credential(1, "alice", "{hash}")));

        UserDetails user = service.loadUserByUsername("alice");

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getPassword()).isEqualTo("{hash}");
    }

    @Test
    void anUnknownUserIsNotFound() {
        when(credentials.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }
}
