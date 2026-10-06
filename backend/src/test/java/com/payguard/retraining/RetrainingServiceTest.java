package com.payguard.retraining;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.audit.AuditLogRepository;
import com.payguard.decision.Decision;
import com.payguard.retraining.dto.ActivationGateResult;
import com.payguard.retraining.dto.AssembledTrainingData;
import com.payguard.retraining.dto.EligibilityResult;
import com.payguard.retraining.dto.TrainingDataSummary;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.verdict.AnalystVerdict;
import com.payguard.verdict.AnalystVerdictRepository;
import com.payguard.verdict.VerdictType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetrainingServiceTest {

    @Mock
    private AnalystVerdictRepository analystVerdictRepository;

    @Mock
    private ModelVersionRepository modelVersionRepository;

    @Mock
    private MLRetrainingClient mlRetrainingClient;

    @Mock
    private AuditLogRepository auditLogRepository;

    private RetrainingProperties retrainingProperties;

    private RetrainingService retrainingService;

    @BeforeEach
    void setUp() {
        retrainingProperties = new RetrainingProperties();
        retrainingProperties.setMinTotalVerdicts(20);
        retrainingProperties.setMinPerClass(5);
        retrainingProperties.setMinPrAucImprovementTolerance(-0.02);
        retrainingProperties.setTimeoutMs(120000);

        retrainingService = new RetrainingService(
                analystVerdictRepository,
                modelVersionRepository,
                mlRetrainingClient,
                retrainingProperties,
                auditLogRepository
        );
    }

    @Test
    @DisplayName("Eligibility Gate: Fails when total verdicts < min-total-verdicts (20)")
    void testEligibilityFailsWhenTotalUnderTwenty() {
        TrainingDataSummary summary = new TrainingDataSummary(19, 10, 9);
        EligibilityResult result = retrainingService.checkEligibility(summary);

        assertFalse(result.isEligible());
        assertTrue(result.getReason().contains("19"));
        assertTrue(result.getReason().contains("payguard.retraining.min-total-verdicts"));
    }

    @Test
    @DisplayName("Eligibility Gate: Fails when fraud count < min-per-class (5)")
    void testEligibilityFailsWhenFraudUnderFive() {
        TrainingDataSummary summary = new TrainingDataSummary(25, 4, 21);
        EligibilityResult result = retrainingService.checkEligibility(summary);

        assertFalse(result.isEligible());
        assertTrue(result.getReason().contains("fraud"));
        assertTrue(result.getReason().contains("payguard.retraining.min-per-class"));
    }

    @Test
    @DisplayName("Eligibility Gate: Fails when legitimate count < min-per-class (5)")
    void testEligibilityFailsWhenLegitimateUnderFive() {
        TrainingDataSummary summary = new TrainingDataSummary(25, 22, 3);
        EligibilityResult result = retrainingService.checkEligibility(summary);

        assertFalse(result.isEligible());
        assertTrue(result.getReason().contains("legitimate"));
        assertTrue(result.getReason().contains("payguard.retraining.min-per-class"));
    }

    @Test
    @DisplayName("Eligibility Gate: Passes when total >= 20 and both classes >= 5")
    void testEligibilityPassesWhenCriteriaMet() {
        TrainingDataSummary summary = new TrainingDataSummary(20, 5, 15);
        EligibilityResult result = retrainingService.checkEligibility(summary);

        assertTrue(result.isEligible());
        assertTrue(result.getReason().contains("Eligible"));
    }

    @Test
    @DisplayName("Activation Gate: Passes when candidate PR-AUC is within tolerance (-0.02)")
    void testActivationGatePassesWithinTolerance() {
        // active = 0.84803, tolerance = -0.02, threshold = 0.82803
        // candidate = 0.83500 >= 0.82803 -> PASS
        Map<String, Object> candidateMetrics = Map.of("test_pr_auc", 0.83500);
        Map<String, Object> activeMetrics = Map.of("pr_auc", 0.84803);

        ActivationGateResult result = retrainingService.evaluateActivationGate(candidateMetrics, activeMetrics);

        assertTrue(result.isPassed());
        assertEquals(0.83500, result.getCandidatePrAuc(), 0.00001);
        assertEquals(0.84803, result.getActivePrAuc(), 0.00001);
        assertEquals(-0.02, result.getTolerance(), 0.00001);
        assertTrue(result.getReason().contains("passed"));
    }

    @Test
    @DisplayName("Activation Gate: Fails when candidate PR-AUC is below active + tolerance")
    void testActivationGateFailsBelowTolerance() {
        // active = 0.84803, tolerance = -0.02, threshold = 0.82803
        // candidate = 0.81000 < 0.82803 -> FAIL
        Map<String, Object> candidateMetrics = Map.of("test_pr_auc", 0.81000);
        Map<String, Object> activeMetrics = Map.of("pr_auc", 0.84803);

        ActivationGateResult result = retrainingService.evaluateActivationGate(candidateMetrics, activeMetrics);

        assertFalse(result.isPassed());
        assertEquals(0.81000, result.getCandidatePrAuc(), 0.00001);
        assertTrue(result.getReason().contains("failed"));
    }

    @Test
    @DisplayName("generateCandidateVersionName increments max version number correctly")
    void testCandidateVersionNaming() {
        ModelVersion v1 = new ModelVersion();
        v1.setVersion("xgboost-v1");
        ModelVersion v2 = new ModelVersion();
        v2.setVersion("xgboost-v2");

        when(modelVersionRepository.findAll()).thenReturn(List.of(v1, v2));

        String nextName = retrainingService.generateCandidateVersionName();
        assertEquals("xgboost-v3", nextName);
    }

    @Test
    @DisplayName("assembleTrainingData correctly builds 11-feature contract examples and counts")
    void testAssembleTrainingData() {
        UUID alertId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();

        AnalystVerdict verdictFraud = new AnalystVerdict(UUID.randomUUID(), alertId, UUID.randomUUID(),
                VerdictType.FRAUD, "confirmed fraud", OffsetDateTime.now());

        Alert alert = new Alert(alertId, txnId, BigDecimal.valueOf(0.75), BigDecimal.valueOf(80.0),
                BigDecimal.valueOf(0.85), Decision.BLOCK, SeverityLevel.HIGH, AlertStatus.RESOLVED,
                Map.of(), OffsetDateTime.now(), OffsetDateTime.now());

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setAmount(new BigDecimal("150.00"));

        TransactionFeature tf = new TransactionFeature();
        tf.setTransactionRefId(txnId);
        tf.setTransactionsLast2Min(2);
        tf.setTransactionsLast1Hour(5);
        tf.setAccountAvgAmount(new BigDecimal("50.00"));
        tf.setAmountRatio(new BigDecimal("3.0000"));
        tf.setTimeSincePreviousTransactionSeconds(30L);
        tf.setNewDevice(true);
        tf.setNewLocation(false);
        tf.setTransactionHour((short) 14);
        tf.setOddHour(false);

        List<Object[]> queryResult = new ArrayList<>();
        queryResult.add(new Object[]{verdictFraud, alert, transaction, tf});

        when(analystVerdictRepository.findAllVerdictedRecords()).thenReturn(queryResult);

        AssembledTrainingData assembled = retrainingService.assembleTrainingData();
        assertEquals(1, assembled.getExamples().size());
        assertEquals(1, assembled.getSummary().getTotalVerdicts());
        assertEquals(1, assembled.getSummary().getFraudCount());
        assertEquals(0, assembled.getSummary().getLegitimateCount());

        Map<String, Object> featMap = assembled.getExamples().get(0).getFeatures();
        assertEquals(150.00, featMap.get("amount"));
        assertEquals(2, featMap.get("transactions_last_2_min"));
        assertEquals(5, featMap.get("transactions_last_1_hour"));
        assertEquals(50.00, featMap.get("account_avg_amount"));
        assertEquals(3.0, featMap.get("amount_ratio"));
        assertEquals(30.0, featMap.get("time_since_previous_seconds"));
        assertEquals(0, featMap.get("is_first_transaction"));
        assertEquals(1, featMap.get("new_device"));
        assertEquals(0, featMap.get("new_location"));
        assertEquals(14, featMap.get("transaction_hour"));
        assertEquals(0, featMap.get("odd_hour"));
        assertEquals(1, assembled.getExamples().get(0).getLabel());
    }

    @Test
    @DisplayName("Portability: normalizeModelPath preserves clean relative path")
    void testNormalizeModelPathWithRelativePath() {
        String result = retrainingService.normalizeModelPath("xgboost-v2", "model_store/xgboost-v2");
        assertEquals("model_store/xgboost-v2", result);
        assertTrue(result.matches("^model_store/[a-zA-Z0-9_-]+$"), "Path must match relative pattern");
    }

    @Test
    @DisplayName("Portability: normalizeModelPath converts Windows absolute path to relative format")
    void testNormalizeModelPathWithAbsoluteWindowsPath() {
        String winPath = "C:\\Users\\Ishika Chauhan\\Desktop\\PayGuard\\ml-service\\model_store\\xgboost-v2";
        String result = retrainingService.normalizeModelPath("xgboost-v2", winPath);
        assertEquals("model_store/xgboost-v2", result);
        assertFalse(result.contains(":"), "Normalized path must not contain Windows drive letter");
        assertFalse(result.contains("\\"), "Normalized path must use forward slashes");
        assertTrue(result.matches("^model_store/[a-zA-Z0-9_-]+$"), "Path must match relative pattern");
    }

    @Test
    @DisplayName("Portability: normalizeModelPath falls back to model_store/{version} when null or empty")
    void testNormalizeModelPathWithNullOrEmpty() {
        assertEquals("model_store/xgboost-v2", retrainingService.normalizeModelPath("xgboost-v2", null));
        assertEquals("model_store/xgboost-v2", retrainingService.normalizeModelPath("xgboost-v2", "   "));
    }

    @Test
    @DisplayName("Portability: recordActiveCandidateInDb always persists relative portable path")
    void testRecordActiveCandidateStoresRelativeModelPath() {
        when(modelVersionRepository.saveAndFlush(any(ModelVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String absPath = "C:\\PayGuard\\ml-service\\model_store\\xgboost-v3";
        ModelVersion saved = retrainingService.recordActiveCandidateInDb("xgboost-v3", Map.of("pr_auc", 0.86), absPath);

        assertNotNull(saved);
        assertEquals("model_store/xgboost-v3", saved.getModelPath());
        assertTrue(saved.getModelPath().matches("^model_store/[a-zA-Z0-9_-]+$"));
        assertFalse(saved.getModelPath().startsWith("C:"));
    }
}

