package com.payguard.security;

import com.payguard.user.User;
import com.payguard.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-safety";
    private static final long TEST_EXPIRATION_MS = 3600000; // 1 hour

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
    }

    @Test
    @DisplayName("Generate JWT token and validate claims, subject, role, and expiration")
    void testGenerateAndValidateToken() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("analyst_jane");
        user.setEmail("jane@payguard.com");
        user.setRole(UserRole.ANALYST);

        String token = jwtService.generateToken(user);
        assertNotNull(token);
        assertFalse(token.isBlank());

        // Extract subject / username
        assertEquals("analyst_jane", jwtService.extractUsername(token));

        // Extract role claim
        assertEquals(UserRole.ANALYST.name(), jwtService.extractRole(token));

        // Expiration in the future
        Date expiration = jwtService.extractExpiration(token);
        assertTrue(expiration.after(new Date()));
        assertFalse(jwtService.isTokenExpired(token));

        // Token validation
        assertTrue(jwtService.validateToken(token));
        assertTrue(jwtService.validateToken(token, "analyst_jane"));
        assertFalse(jwtService.validateToken(token, "different_user"));
    }

    @Test
    @DisplayName("Validate token returns false for expired token")
    void testExpiredTokenValidation() {
        // Create JwtService with negative expiration (already expired)
        JwtService expiredJwtService = new JwtService(TEST_SECRET, -1000);

        String expiredToken = expiredJwtService.generateToken("expired_user", "ADMIN");
        assertNotNull(expiredToken);

        assertFalse(jwtService.validateToken(expiredToken));
        assertTrue(jwtService.isTokenExpired(expiredToken));
    }

    @Test
    @DisplayName("Validate token returns false for malformed or tampered token")
    void testTamperedTokenValidation() {
        String token = jwtService.generateToken("user1", "ADMIN");
        String tamperedToken = token + "xyz";

        assertFalse(jwtService.validateToken(tamperedToken));
        assertFalse(jwtService.validateToken("not.a.valid.jwt.token"));
        assertFalse(jwtService.validateToken(""));
        assertFalse(jwtService.validateToken(null));
    }
}
