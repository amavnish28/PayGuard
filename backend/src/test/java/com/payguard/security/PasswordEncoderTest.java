package com.payguard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncoderTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("BCrypt password hashing generates valid salted hash and verifies successfully")
    void testPasswordHashingAndVerification() {
        String rawPassword = "securePassword123!";

        String hash1 = passwordEncoder.encode(rawPassword);
        String hash2 = passwordEncoder.encode(rawPassword);

        // Hashes must be non-null and not equal to the plaintext
        assertNotNull(hash1);
        assertNotEquals(rawPassword, hash1);
        assertTrue(hash1.startsWith("$2a$") || hash1.startsWith("$2b$"));

        // Salts ensure distinct hashes for identical passwords
        assertNotEquals(hash1, hash2);

        // Verification matches
        assertTrue(passwordEncoder.matches(rawPassword, hash1));
        assertTrue(passwordEncoder.matches(rawPassword, hash2));

        // Wrong password fails
        assertFalse(passwordEncoder.matches("wrongPassword", hash1));
        assertFalse(passwordEncoder.matches("", hash1));
    }
}
