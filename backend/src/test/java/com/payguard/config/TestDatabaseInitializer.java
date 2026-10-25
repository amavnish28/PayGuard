package com.payguard.config;

import com.payguard.retraining.ModelVersion;
import com.payguard.retraining.ModelVersionRepository;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(1)
public class TestDatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDatabaseInitializer.class);

    private final ModelVersionRepository modelVersionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TestDatabaseInitializer(ModelVersionRepository modelVersionRepository,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder) {
        this.modelVersionRepository = modelVersionRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedModelVersion();
        seedTestUsers();
    }

    public void seedModelVersion() {
        if (modelVersionRepository.findByVersion("xgboost-v1").isEmpty()) {
            log.info("Seeding test database with baseline active model_version 'xgboost-v1'...");
            ModelVersion v1 = new ModelVersion();
            v1.setVersion("xgboost-v1");
            v1.setModelName("production-xgboost");
            v1.setAlgorithm("xgboost");
            v1.setModelPath("model_store/xgboost_v1");
            v1.setIsActive(true);
            v1.setCreatedAt(OffsetDateTime.now());

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("pr_auc", 0.84803);
            metrics.put("test_pr_auc", 0.84803);
            metrics.put("roc_auc", 0.98389);
            metrics.put("test_roc_auc", 0.98389);
            metrics.put("block_threshold", 0.56667);
            metrics.put("review_threshold", 0.18657);
            v1.setMetrics(metrics);

            modelVersionRepository.saveAndFlush(v1);
            log.info("Baseline active model_version 'xgboost-v1' seeded successfully in test database.");
        }
    }

    public void seedTestUsers() {
        createUserIfMissing("admin", "admin@payguard.com", UserRole.ADMIN);
        createUserIfMissing("retrain_admin", "retrain_admin@payguard.com", UserRole.ADMIN);
        createUserIfMissing("retrain_analyst", "retrain_analyst@payguard.com", UserRole.ANALYST);
        createUserIfMissing("testuser", "testuser@payguard.com", UserRole.ANALYST);
    }

    private void createUserIfMissing(String username, String email, UserRole role) {
        if (userRepository.findByUsername(username).isEmpty()) {
            log.info("Seeding test database with user '{}' ({})", username, role);
            User user = new User();
            user.setUsername(username);
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode("testSecret123"));
            user.setRole(role);
            user.setIsActive(true);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.saveAndFlush(user);
        }
    }
}
