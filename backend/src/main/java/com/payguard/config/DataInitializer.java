package com.payguard.config;

import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${PAYGUARD_ADMIN_USERNAME:}")
    private String adminUsername;

    @Value("${PAYGUARD_ADMIN_PASSWORD:}")
    private String adminPassword;

    @Value("${PAYGUARD_ADMIN_EMAIL:}")
    private String adminEmail;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (adminUsername == null || adminUsername.isBlank()
                || adminPassword == null || adminPassword.isBlank()
                || adminEmail == null || adminEmail.isBlank()) {
            log.info("Admin seed environment variables not set; skipping initial admin user creation.");
            return;
        }

        if (userRepository.count() > 0) {
            log.info("Users already exist in database; skipping initial admin user creation.");
            return;
        }

        log.info("Creating initial admin user with username: {}", adminUsername);
        User adminUser = new User();
        adminUser.setUsername(adminUsername.trim());
        adminUser.setEmail(adminEmail.trim());
        adminUser.setPasswordHash(passwordEncoder.encode(adminPassword));
        adminUser.setRole(UserRole.ADMIN);
        adminUser.setIsActive(true);
        adminUser.setCreatedAt(LocalDateTime.now());
        adminUser.setUpdatedAt(LocalDateTime.now());

        userRepository.save(adminUser);
        log.info("Initial admin user '{}' created successfully with role ADMIN.", adminUsername);
    }
}
