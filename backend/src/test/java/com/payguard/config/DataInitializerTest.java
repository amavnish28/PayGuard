package com.payguard.config;

import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private com.payguard.retraining.ModelVersionRepository modelVersionRepository;

    @InjectMocks
    private DataInitializer dataInitializer;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(modelVersionRepository.count()).thenReturn(1L);
    }

    @Test
    @DisplayName("Creates admin user when env vars are present and repository is empty")
    void testCreatesAdminUserWhenEmpty() {
        ReflectionTestUtils.setField(dataInitializer, "adminUsername", "admin");
        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "adminSecret123");
        ReflectionTestUtils.setField(dataInitializer, "adminEmail", "admin@payguard.com");

        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("adminSecret123")).thenReturn("$2a$10$encodedHash");

        dataInitializer.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());

        User savedUser = captor.getValue();
        assertEquals("admin", savedUser.getUsername());
        assertEquals("admin@payguard.com", savedUser.getEmail());
        assertEquals("$2a$10$encodedHash", savedUser.getPasswordHash());
        assertEquals(UserRole.ADMIN, savedUser.getRole());
        assertTrue(savedUser.getIsActive());
    }

    @Test
    @DisplayName("Skips admin user creation when users already exist in database")
    void testSkipsWhenUsersExist() {
        ReflectionTestUtils.setField(dataInitializer, "adminUsername", "admin");
        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "adminSecret123");
        ReflectionTestUtils.setField(dataInitializer, "adminEmail", "admin@payguard.com");

        when(userRepository.count()).thenReturn(1L);

        dataInitializer.run();

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("Skips admin user creation when environment variables are missing")
    void testSkipsWhenEnvVarsMissing() {
        ReflectionTestUtils.setField(dataInitializer, "adminUsername", "");
        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "");
        ReflectionTestUtils.setField(dataInitializer, "adminEmail", "");

        dataInitializer.run();

        verify(userRepository, never()).count();
        verify(userRepository, never()).save(any(User.class));
    }
}
