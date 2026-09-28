package com.payguard.auth;

import com.payguard.auth.dto.LoginRequest;
import com.payguard.auth.dto.LoginResponse;
import com.payguard.security.JwtService;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = new User();
        sampleUser.setId(UUID.randomUUID());
        sampleUser.setUsername("testuser");
        sampleUser.setEmail("test@payguard.com");
        sampleUser.setPasswordHash("$2a$10$hashedPasswordHere");
        sampleUser.setRole(UserRole.ANALYST);
        sampleUser.setIsActive(true);
    }

    @Test
    @DisplayName("Login success returns LoginResponse with token, username, and role")
    void testLoginSuccess() {
        LoginRequest request = new LoginRequest("testuser", "rawPassword123");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("rawPassword123", sampleUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateToken(sampleUser)).thenReturn("mock.jwt.token");

        LoginResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("mock.jwt.token", response.getToken());
        assertEquals("testuser", response.getUsername());
        assertEquals("ANALYST", response.getRole());

        verify(userRepository, times(1)).findByUsername("testuser");
        verify(passwordEncoder, times(1)).matches("rawPassword123", sampleUser.getPasswordHash());
        verify(jwtService, times(1)).generateToken(sampleUser);
    }

    @Test
    @DisplayName("Login failure when user not found throws BadCredentialsException")
    void testLoginFailureUserNotFound() {
        LoginRequest request = new LoginRequest("unknownuser", "password");

        when(userRepository.findByUsername("unknownuser")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authService.login(request));

        verify(userRepository, times(1)).findByUsername("unknownuser");
        verify(passwordEncoder, never()).matches(any(), any());
        verify(jwtService, never()).generateToken(any(User.class));
    }

    @Test
    @DisplayName("Login failure when password does not match throws BadCredentialsException")
    void testLoginFailureWrongPassword() {
        LoginRequest request = new LoginRequest("testuser", "wrongPassword");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("wrongPassword", sampleUser.getPasswordHash())).thenReturn(false);

        assertThrows(BadCredentialsException.class, () -> authService.login(request));

        verify(userRepository, times(1)).findByUsername("testuser");
        verify(passwordEncoder, times(1)).matches("wrongPassword", sampleUser.getPasswordHash());
        verify(jwtService, never()).generateToken(any(User.class));
    }

    @Test
    @DisplayName("Login failure when user is inactive throws BadCredentialsException")
    void testLoginFailureInactiveUser() {
        sampleUser.setIsActive(false);
        LoginRequest request = new LoginRequest("testuser", "rawPassword123");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(sampleUser));

        assertThrows(BadCredentialsException.class, () -> authService.login(request));

        verify(userRepository, times(1)).findByUsername("testuser");
        verify(passwordEncoder, never()).matches(any(), any());
        verify(jwtService, never()).generateToken(any(User.class));
    }
}
