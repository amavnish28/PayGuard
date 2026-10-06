package com.payguard.retraining;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import com.payguard.decision.Decision;
import com.payguard.retraining.dto.RetrainResponseDto;
import com.payguard.security.JwtService;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RetrainingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

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
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private MLRetrainingClient mlRetrainingClient;

    private User adminUser;
    private User analystUser;
    private String adminToken;
    private String analystToken;

    private final List<UUID> createdVerdictIds = new ArrayList<>();
    private final List<UUID> createdAlertIds = new ArrayList<>();
    private final List<UUID> createdTransactionIds = new ArrayList<>();
    private final List<String> createdModelVersions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Ensure baseline admin user exists
        adminUser = userRepository.findByUsername("retrain_admin").orElseGet(() -> {
            User u = new User();
            u.setUsername("retrain_admin");
            u.setEmail("retrain_admin@payguard.com");
            u.setPasswordHash("$2a$10$dummyHash");
            u.setRole(UserRole.ADMIN);
            u.setIsActive(true);
            return userRepository.saveAndFlush(u);
        });
        adminToken = jwtService.generateToken(adminUser);

        // Ensure baseline analyst user exists
        analystUser = userRepository.findByUsername("retrain_analyst").orElseGet(() -> {
            User u = new User();
            u.setUsername("retrain_analyst");
            u.setEmail("retrain_analyst@payguard.com");
            u.setPasswordHash("$2a$10$dummyHash");
            u.setRole(UserRole.ANALYST);
            u.setIsActive(true);
            return userRepository.saveAndFlush(u);
        });
        analystToken = jwtService.generateToken(analystUser);

        // Ensure baseline active model exists and all non-v1 candidate models are cleared
        List<ModelVersion> others = modelVersionRepository.findAll().stream()
                .filter(mv -> !"xgboost-v1".equals(mv.getVersion()))
                .toList();
        if (!others.isEmpty()) {
            modelVersionRepository.deleteAll(others);
            modelVersionRepository.flush();
        }

        ModelVersion v1 = modelVersionRepository.findByVersion("xgboost-v1").orElseGet(() -> {
            ModelVersion mv = new ModelVersion();
            mv.setVersion("xgboost-v1");
            mv.setModelName("production-xgboost");
            mv.setAlgorithm("xgboost");
            mv.setModelPath("model_store/xgboost_v1");
            mv.setIsActive(true);
            mv.setMetrics(Map.of("pr_auc", 0.84803, "test_pr_auc", 0.84803));
            mv.setCreatedAt(OffsetDateTime.now());
            return modelVersionRepository.saveAndFlush(mv);
        });
        v1.setIsActive(true);
        modelVersionRepository.saveAndFlush(v1);

        List<AuditLog> oldRetrainLogs = auditLogRepository.findAll().stream()
                .filter(al -> "MODEL_RETRAIN_ATTEMPTED".equals(al.getAction()))
                .toList();
        if (!oldRetrainLogs.isEmpty()) {
            auditLogRepository.deleteAll(oldRetrainLogs);
            auditLogRepository.flush();
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

        List<ModelVersion> nonV1 = modelVersionRepository.findAll().stream()
                .filter(mv -> !"xgboost-v1".equals(mv.getVersion()))
                .toList();
        if (!nonV1.isEmpty()) {
            modelVersionRepository.deleteAll(nonV1);
            modelVersionRepository.flush();
        }

        // Restore xgboost-v1 as active
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
        String txnId = "TXN-SEED-" + UUID.randomUUID();
        Transaction t = new Transaction();
        t.setTransactionId(txnId);
        t.setAccountId("ACC-" + UUID.randomUUID());
        t.setAmount(new BigDecimal("120.00"));
        t.setCurrency("INR");
        t.setDeviceId("DEV-SEED-1");
        t.setLocation("Mumbai");
        t.setMerchantType("RETAIL");
        t.setTransactionTimestamp(OffsetDateTime.now());
        t = transactionRepository.saveAndFlush(t);
        createdTransactionIds.add(t.getId());

        TransactionFeature tf = new TransactionFeature();
        tf.setTransactionRefId(t.getId());
        tf.setTransactionsLast2Min(1);
        tf.setTransactionsLast1Hour(2);
        tf.setAccountAvgAmount(new BigDecimal("100.00"));
        tf.setAmountRatio(new BigDecimal("1.2000"));
        tf.setTimeSincePreviousTransactionSeconds(450L);
        tf.setNewDevice(false);
        tf.setNewLocation(false);
        tf.setTransactionHour((short) 15);
        tf.setOddHour(false);
        transactionFeatureRepository.saveAndFlush(tf);

        Alert a = new Alert();
        a.setTransactionId(t.getId());
        a.setDecision(Decision.REVIEW);
        a.setStatus(AlertStatus.RESOLVED);
        a.setFraudProbability(new BigDecimal("0.3500"));
        a.setRuleScore(new BigDecimal("25.00"));
        a.setFinalScore(new BigDecimal("0.3500"));
        a = alertRepository.saveAndFlush(a);
        createdAlertIds.add(a.getId());

        AnalystVerdict v = new AnalystVerdict();
        v.setAlertId(a.getId());
        v.setAnalystId(adminUser.getId());
        v.setVerdict(type);
        v.setComment("Seeded test verdict");
        v.setCreatedAt(OffsetDateTime.now());
        v = analystVerdictRepository.saveAndFlush(v);
        createdVerdictIds.add(v.getId());
    }

    @Test
    @DisplayName("Security: Unauthenticated request returns 401 Unauthorized")
    void testUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/retrain")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Security: ANALYST role returns 403 Forbidden (ADMIN only endpoint)")
    void testAnalystRoleReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/retrain")
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Eligibility-fail path: Under minimum verdicts returns 400 and FastAPI is NEVER called")
    void testEligibilityFailPathUnderMinimumVerdicts() throws Exception {
        // Clear all verdicts for clean eligibility-fail test
        analystVerdictRepository.deleteAll();

        // Seed only 3 fraud and 2 legit verdicts (5 total < 20 min)
        seedVerdicts(3, 2);

        mockMvc.perform(post("/api/v1/admin/retrain")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").isString());

        // Verify FastAPI retrain was NEVER called
        verify(mlRetrainingClient, never()).retrain(anyString(), anyList());
        verify(mlRetrainingClient, never()).activate(anyString());

        // Verify no candidate row in model_versions
        List<ModelVersion> candidates = modelVersionRepository.findAll().stream()
                .filter(mv -> !"xgboost-v1".equals(mv.getVersion()))
                .toList();
        assertTrue(candidates.isEmpty(), "No new candidate row should be created on eligibility failure");

        // Verify audit log has MODEL_RETRAIN_ATTEMPTED with eligible = false
        AuditLog latest = auditLogRepository.findAll().stream()
                .filter(al -> "MODEL_RETRAIN_ATTEMPTED".equals(al.getAction()))
                .max(Comparator.comparing(AuditLog::getCreatedAt))
                .orElseThrow();
        assertEquals(Boolean.FALSE, latest.getDetails().get("eligible"));
    }

    @Test
    @DisplayName("Activation-gate-fail path: Low PR-AUC candidate creates inactive row and does NOT call activate")
    void testActivationGateFailPath() throws Exception {
        // Seed sufficient verdicts: 15 fraud, 10 legit (25 total >= 20, each >= 5)
        seedVerdicts(15, 10);

        // Candidate response with PR-AUC = 0.70 (below 0.84803 - 0.02 = 0.82803)
        Map<String, Object> candidateMetrics = new LinkedHashMap<>();
        candidateMetrics.put("test_pr_auc", 0.70000);
        candidateMetrics.put("pr_auc", 0.70000);
        candidateMetrics.put("roc_auc", 0.95000);

        RetrainResponseDto mockResponse = new RetrainResponseDto(
                "xgboost-v2",
                candidateMetrics,
                Map.of("total_rows", 100),
                "model_store/xgboost-v2",
                0.55,
                0.20
        );

        when(mlRetrainingClient.retrain(anyString(), anyList())).thenReturn(mockResponse);

        createdModelVersions.add("xgboost-v2");

        mockMvc.perform(post("/api/v1/admin/retrain")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.candidateVersion").value("xgboost-v2"))
                .andExpect(jsonPath("$.activated").value(false))
                .andExpect(jsonPath("$.reason").isString());

        // Verify FastAPI /model/activate was NEVER called
        verify(mlRetrainingClient, never()).activate(anyString());

        // Verify candidate row created in DB with is_active = false
        ModelVersion candidate = modelVersionRepository.findByVersion("xgboost-v2").orElseThrow();
        assertFalse(candidate.getIsActive(), "Candidate row must have is_active=false when activation gate fails");

        // Verify active model is still xgboost-v1 with is_active = true
        ModelVersion active = modelVersionRepository.findByVersion("xgboost-v1").orElseThrow();
        assertTrue(active.getIsActive(), "Previously active model must remain active");

        // Verify audit log recorded attempt with activationGatePassed = false
        AuditLog latest = auditLogRepository.findAll().stream()
                .filter(al -> "MODEL_RETRAIN_ATTEMPTED".equals(al.getAction()))
                .max(Comparator.comparing(AuditLog::getCreatedAt))
                .orElseThrow();
        assertEquals(Boolean.TRUE, latest.getDetails().get("eligible"));
        assertEquals(Boolean.FALSE, latest.getDetails().get("activationGatePassed"));
        assertEquals("xgboost-v1", latest.getDetails().get("activeBefore"));
        assertEquals("xgboost-v1", latest.getDetails().get("activeAfter"));
    }

    @Test
    @DisplayName("Full eligibility-pass path: Candidate passes gate, activates in DB and calls FastAPI activate")
    void testFullEligibilityPassAndActivationPath() throws Exception {
        // Seed sufficient verdicts: 12 fraud, 10 legit (22 total)
        seedVerdicts(12, 10);

        // Candidate response with PR-AUC = 0.86500 (above 0.84803 - 0.02 = 0.82803)
        Map<String, Object> candidateMetrics = new LinkedHashMap<>();
        candidateMetrics.put("test_pr_auc", 0.86500);
        candidateMetrics.put("pr_auc", 0.86500);
        candidateMetrics.put("roc_auc", 0.98500);

        RetrainResponseDto mockResponse = new RetrainResponseDto(
                "xgboost-v2",
                candidateMetrics,
                Map.of("total_rows", 200),
                "model_store/xgboost-v2",
                0.56,
                0.19
        );

        when(mlRetrainingClient.retrain(anyString(), anyList())).thenReturn(mockResponse);
        doNothing().when(mlRetrainingClient).activate("xgboost-v2");

        createdModelVersions.add("xgboost-v2");

        mockMvc.perform(post("/api/v1/admin/retrain")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.candidateVersion").value("xgboost-v2"))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.reason").isString());

        // Verify FastAPI /model/activate WAS called with candidate version
        verify(mlRetrainingClient, times(1)).activate("xgboost-v2");

        // Verify new candidate row exists in DB with is_active = true and relative model_path
        ModelVersion candidate = modelVersionRepository.findByVersion("xgboost-v2").orElseThrow();
        assertTrue(candidate.getIsActive(), "Candidate row must be is_active=true after successful activation");
        assertEquals("model_store/xgboost-v2", candidate.getModelPath());
        assertTrue(candidate.getModelPath().matches("^model_store/[a-zA-Z0-9_-]+$"), "model_path must be portable relative format");
        assertFalse(candidate.getModelPath().contains(":"), "model_path must not contain drive letter");

        // Verify previous version is now is_active = false
        ModelVersion previous = modelVersionRepository.findByVersion("xgboost-v1").orElseThrow();
        assertFalse(previous.getIsActive(), "Previously active model must be deactivated (is_active=false)");

        // Verify audit log has complete information
        AuditLog latest = auditLogRepository.findAll().stream()
                .filter(al -> "MODEL_RETRAIN_ATTEMPTED".equals(al.getAction()))
                .max(Comparator.comparing(AuditLog::getCreatedAt))
                .orElseThrow();
        assertEquals(Boolean.TRUE, latest.getDetails().get("eligible"));
        assertEquals(Boolean.TRUE, latest.getDetails().get("activationGatePassed"));
        assertEquals("xgboost-v1", latest.getDetails().get("activeBefore"));
        assertEquals("xgboost-v2", latest.getDetails().get("activeAfter"));
    }
}
