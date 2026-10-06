package com.payguard.config;

import com.payguard.retraining.ModelVersion;
import com.payguard.retraining.ModelVersionRepository;
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
import java.time.OffsetDateTime;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final ModelVersionRepository modelVersionRepository;

    @Value("${PAYGUARD_ADMIN_USERNAME:}")
    private String adminUsername;

    @Value("${PAYGUARD_ADMIN_PASSWORD:}")
    private String adminPassword;

    @Value("${PAYGUARD_ADMIN_EMAIL:}")
    private String adminEmail;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           ModelVersionRepository modelVersionRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.modelVersionRepository = modelVersionRepository;
    }

    @Override
    public void run(String... args) {
        seedInitialAdminUser();
        seedInitialActiveModelVersion();
    }

    private void seedInitialAdminUser() {
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

    private void seedInitialActiveModelVersion() {
        if (modelVersionRepository.count() > 0) {
            log.info("Model versions already exist in database; skipping initial model_version seeding.");
            return;
        }

        log.info("Seeding initial active model_version 'xgboost-v1' into model_versions table...");
        ModelVersion initialVersion = new ModelVersion();
        initialVersion.setVersion("xgboost-v1");
        initialVersion.setModelName("production-xgboost");
        initialVersion.setAlgorithm("xgboost");
        initialVersion.setModelPath("model_store/xgboost_v1");
        initialVersion.setIsActive(true);
        initialVersion.setCreatedAt(OffsetDateTime.now());

        java.util.Map<String, Object> initialMetrics = new java.util.LinkedHashMap<>();
        initialMetrics.put("pr_auc", 0.84803);
        initialMetrics.put("test_pr_auc", 0.84803);
        initialMetrics.put("roc_auc", 0.98389);
        initialMetrics.put("test_roc_auc", 0.98389);
        initialMetrics.put("block_threshold", 0.56667);
        initialMetrics.put("review_threshold", 0.18657);
        initialVersion.setMetrics(initialMetrics);

        modelVersionRepository.save(initialVersion);
        log.info("Initial active model_version 'xgboost-v1' seeded successfully with stored PR-AUC 0.84803.");
    }
}
