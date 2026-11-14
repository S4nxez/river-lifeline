package com.nisarsar.config;

import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    @Bean("JWT")
    public Key jwtKey() {
        return Keys.secretKeyFor(SignatureAlgorithm.HS256);
    }
}
