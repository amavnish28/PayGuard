package com.payguard.retraining;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.decision.Decision;
import com.payguard.retraining.dto.RetrainAdminResponse;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import com.payguard.transaction.TransactionService;
import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.transaction.feature.TransactionFeatureRepository;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import com.payguard.verdict.AnalystVerdict;
import com.payguard.verdict.AnalystVerdictRepository;
import com.payguard.verdict.VerdictType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("requires-ml-service")
@SpringBootTest
class RealFastAPIRetrainingIntegrationTest {

    @Autowired
    private RetrainingService retrainingService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalystVerdictRepository analystVerdictRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionFeatureRepository transactionFeatureRepository;

    @Autowired
    private ModelVersionRepository modelVersionRepository;

    @Autowired
    private MLRetrainingClient mlRetrainingClient;

    private User adminUser;
    private final List<UUID> createdVerdictIds = new ArrayList<>();
    private final List<UUID> createdAlertIds = new ArrayList<>();
    private final List<UUID> createdTransactionIds = new ArrayList<>();
    private final List<String> createdModelVersions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        adminUser = userRepository.findByUsername("admin_e2e").orElseGet(() -> {
            User u = new User();
            u.setUsername("admin_e2e");
            u.setEmail("admin_e2e@payguard.com");
            u.setPasswordHash("$2a$10$dummyHash");
            u.setRole(UserRole.ADMIN);
            u.setIsActive(true);
            return userRepository.saveAndFlush(u);
        });

        // Ensure baseline active model exists
        if (modelVersionRepository.findByVersion("xgboost-v1").isEmpty()) {
            ModelVersion v1 = new ModelVersion();
            v1.setVersion("xgboost-v1");
            v1.setModelName("production-xgboost");
            v1.setAlgorithm("xgboost");
            v1.setModelPath("model_store/xgboost_v1");
            v1.setIsActive(true);
            v1.setMetrics(Map.of("pr_auc", 0.84803, "test_pr_auc", 0.84803));
            v1.setCreatedAt(OffsetDateTime.now());
            modelVersionRepository.saveAndFlush(v1);
        } else {
            ModelVersion v1 = modelVersionRepository.findByVersion("xgboost-v1").get();
            v1.setIsActive(true);
            modelVersionRepository.saveAndFlush(v1);
        }
    }

    @AfterEach
    void tearDown() {
        for (UUID vid : createdVerdictIds) {
            try { analystVerdictRepository.deleteById(vid); } catch (Exception ignored) {}
        }
        for (UUID aid : createdAlertIds) {
            try { alertRepository.deleteById(aid); } catch (Exception ignored) {}
        }
        for (UUID tid : createdTransactionIds) {
            try {
                transactionFeatureRepository.deleteByTransactionRefId(tid);
                transactionRepository.deleteById(tid);
            } catch (Exception ignored) {}
        }
        for (String v : createdModelVersions) {
            try { modelVersionRepository.findByVersion(v).ifPresent(modelVersionRepository::delete); } catch (Exception ignored) {}
        }
        createdVerdictIds.clear();
        createdAlertIds.clear();
        createdTransactionIds.clear();
        createdModelVersions.clear();

        // Restore xgboost-v1 as active
        try {
            mlRetrainingClient.activate("xgboost-v1");
        } catch (Exception ignored) {}
        modelVersionRepository.findByVersion("xgboost-v1").ifPresent(v1 -> {
            v1.setIsActive(true);
            modelVersionRepository.saveAndFlush(v1);
        });
    }

    private void seedVerdicts(int fraudCount, int legitCount) {
        for (int i = 0; i < fraudCount; i++) {
            createVerdictRecord(VerdictType.FRAUD);
        }
        for (int i = 0; i < legitCount; i++) {
            createVerdictRecord(VerdictType.LEGITIMATE);
        }
    }

    private void createVerdictRecord(VerdictType type) {
        String txnId = "TXN-E2E-" + UUID.randomUUID();
        Transaction t = new Transaction();
        t.setTransactionId(txnId);
        t.setAccountId("ACC-" + UUID.randomUUID());
        t.setAmount(new BigDecimal("150.00"));
        t.setCurrency("INR");
        t.setDeviceId("DEV-E2E-1");
        t.setLocation("Bangalore");
        t.setMerchantType("DINING");
        t.setTransactionTimestamp(OffsetDateTime.now());
        t = transactionRepository.saveAndFlush(t);
        createdTransactionIds.add(t.getId());

        TransactionFeature tf = new TransactionFeature();
        tf.setTransactionRefId(t.getId());
        tf.setTransactionsLast2Min(0);
        tf.setTransactionsLast1Hour(1);
        tf.setAccountAvgAmount(new BigDecimal("150.00"));
        tf.setAmountRatio(BigDecimal.ONE);
        tf.setTimeSincePreviousTransactionSeconds(600L);
        tf.setNewDevice(false);
        tf.setNewLocation(false);
        tf.setTransactionHour((short) 13);
        tf.setOddHour(false);
        transactionFeatureRepository.saveAndFlush(tf);

        Alert a = new Alert();
        a.setTransactionId(t.getId());
        a.setDecision(Decision.REVIEW);
        a.setStatus(AlertStatus.RESOLVED);
        a.setFraudProbability(new BigDecimal("0.4000"));
        a.setRuleScore(new BigDecimal("30.00"));
        a.setFinalScore(new BigDecimal("0.4000"));
        a = alertRepository.saveAndFlush(a);
        createdAlertIds.add(a.getId());

        AnalystVerdict v = new AnalystVerdict();
        v.setAlertId(a.getId());
        v.setAnalystId(adminUser.getId());
        v.setVerdict(type);
        v.setComment("E2E seeded verdict");
        v.setCreatedAt(OffsetDateTime.now());
        v = analystVerdictRepository.saveAndFlush(v);
        createdVerdictIds.add(v.getId());
    }

    @Test
    @DisplayName("Real FastAPI Integration: retrain workflow trains candidate and serving remains fully functional")
    void testRealFastAPIRetrainingWorkflow() {
        // Seed >=20 verdicts (12 fraud, 10 legit)
        seedVerdicts(12, 10);

        RetrainAdminResponse response = retrainingService.executeRetrainWorkflow(adminUser.getId());
        assertNotNull(response);
        assertTrue(response.isEligible());
        assertNotNull(response.getCandidateVersion());
        createdModelVersions.add(response.getCandidateVersion());

        // Verify POST /api/transactions still produces correct predictions
        String txnId = "TXN-AFTER-RETRAIN-" + UUID.randomUUID();
        createdTransactionIds.add(null); // marker
        TransactionRequest req = new TransactionRequest(
                txnId,
                "ACC-AFTER-" + UUID.randomUUID(),
                new BigDecimal("50.00"),
                "INR",
                "DEV-NORMAL",
                "Delhi",
                "RETAIL",
                OffsetDateTime.now()
        );

        TransactionResponse txResp = transactionService.createTransaction(req);
        assertNotNull(txResp);
        assertEquals("APPROVE", txResp.getDecision());
        assertTrue(txResp.getMlAvailable());
        assertNotNull(txResp.getFraudProbability());

        // Clean up created transaction
        transactionRepository.findByTransactionId(txnId).ifPresent(t -> {
            transactionFeatureRepository.deleteByTransactionRefId(t.getId());
            transactionRepository.delete(t);
        });
    }
}
