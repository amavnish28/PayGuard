package com.payguard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.auth.dto.LoginRequest;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserRepository userRepository;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = new User();
        sampleUser.setId(UUID.randomUUID());
        sampleUser.setUsername("alice");
        sampleUser.setEmail("alice@payguard.com");
        sampleUser.setPasswordHash(passwordEncoder.encode("correctPassword123"));
        sampleUser.setRole(UserRole.ANALYST);
        sampleUser.setIsActive(true);
    }

    @Test
    @DisplayName("POST /api/auth/login with valid credentials returns 200 and token")
    void testLoginSuccess() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(sampleUser));

        LoginRequest request = new LoginRequest("alice", "correctPassword123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("ANALYST"));
    }

    @Test
    @DisplayName("POST /api/auth/login with wrong password returns 401 Unauthorized")
    void testLoginFailureWrongPassword() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(sampleUser));

        LoginRequest request = new LoginRequest("alice", "wrongPassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("POST /api/auth/login with unknown user returns 401 Unauthorized")
    void testLoginFailureUnknownUser() throws Exception {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("nonexistent", "somePassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Unauthenticated request to protected endpoint returns 401 Unauthorized")
    void testUnauthenticatedProtectedRequest() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Authenticated request with valid JWT to protected endpoint returns 200 OK")
    void testAuthenticatedRequestWithValidJwt() throws Exception {
        String token = jwtService.generateToken(sampleUser);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    @DisplayName("Request with invalid or malformed JWT returns 401 Unauthorized")
    void testRequestWithInvalidJwt() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer invalid-tampered-token"))
                .andExpect(status().isUnauthorized());
    }
}
