package com.shopease.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-32+chars";
    private static final long EXPIRATION_MS = 60_000L;

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, EXPIRATION_MS);
    }

    @Test
    @DisplayName("generateToken returns a non-blank JWT")
    void generateToken_returnsNonBlankToken() {
        String token = tokenProvider.generateToken("user@example.com");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("getEmailFromToken returns the subject we signed")
    void getEmailFromToken_returnsOriginalSubject() {
        String token = tokenProvider.generateToken("user@example.com");

        String email = tokenProvider.getEmailFromToken(token);

        assertThat(email).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("validateToken returns true for a freshly issued token")
    void validateToken_validToken_returnsTrue() {
        String token = tokenProvider.generateToken("user@example.com");

        assertThat(tokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("validateToken returns false for a token signed by a different key")
    void validateToken_wrongSignature_returnsFalse() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "different-secret-different-secret-different-secret".getBytes(StandardCharsets.UTF_8));
        String foreignToken = Jwts.builder()
                .subject("attacker@example.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();

        assertThat(tokenProvider.validateToken(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false for an expired token")
    void validateToken_expiredToken_returnsFalse() {
        JwtTokenProvider shortLived = new JwtTokenProvider(SECRET, 1L);
        String token = shortLived.generateToken("user@example.com");

        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(shortLived.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false for a malformed string")
    void validateToken_malformed_returnsFalse() {
        assertThat(tokenProvider.validateToken("not-a-real-jwt")).isFalse();
    }
}
