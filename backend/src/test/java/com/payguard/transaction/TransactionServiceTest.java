package com.payguard.transaction;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import com.payguard.decision.Decision;
import com.payguard.decision.HybridDecisionEngine;
import com.payguard.decision.HybridDecisionResult;
import com.payguard.ml.MLPredictionRequest;
import com.payguard.ml.MLPredictionResponse;
import com.payguard.ml.MLServiceClient;
import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import com.payguard.transaction.feature.BehavioralFeatureService;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.transaction.feature.TransactionFeatureRepository;
import com.payguard.transaction.rule.FraudRulesEngine;
import com.payguard.transaction.rule.RuleEvaluation;
import com.payguard.transaction.rule.RuleTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionFeatureRepository transactionFeatureRepository;

    @Mock
    private BehavioralFeatureService behavioralFeatureService;

    @Mock
    private FraudRulesEngine fraudRulesEngine;

    @Mock
    private MLServiceClient mlServiceClient;

    @Mock
    private HybridDecisionEngine hybridDecisionEngine;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private TransactionService transactionService;

    private TransactionRequest sampleRequest;

    @BeforeEach
    void setUp() {
        sampleRequest = new TransactionRequest(
                "TXN-" + UUID.randomUUID(),
                "ACC-001",
                new BigDecimal("1500.00"),
                "INR",
                "DEV-001",
                "Delhi",
                "RETAIL",
                OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );
    }

    @Test
    @DisplayName("createTransaction success with APPROVE decision creates no alert row and no audit row")
    void testCreateTransactionSuccess() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        BehavioralFeatureResult featureResult = new BehavioralFeatureResult(
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, true, true, (short) 10, false, false
        );
        when(behavioralFeatureService.calculateFeatures(any(Transaction.class))).thenReturn(featureResult);

        RuleEvaluation ruleEvaluation = new RuleEvaluation(0, Collections.emptyList(), Collections.emptyList());
        when(fraudRulesEngine.evaluate(any(Transaction.class), any(BehavioralFeatureResult.class))).thenReturn(ruleEvaluation);

        MLPredictionResponse mlResponse = new MLPredictionResponse(0.001, "xgboost-v1", 0.18, 0.56, "low", null, 10.0);
        when(mlServiceClient.predict(any(MLPredictionRequest.class))).thenReturn(Optional.of(mlResponse));

        HybridDecisionResult decisionResult = new HybridDecisionResult(
                Decision.APPROVE,
                RuleTier.LOW,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                0.001,
                "low",
                null,
                0.001
        );
        when(hybridDecisionEngine.evaluate(any(RuleEvaluation.class), any())).thenReturn(decisionResult);

        TransactionResponse response = transactionService.createTransaction(sampleRequest);

        assertNotNull(response);
        assertEquals(sampleRequest.getTransactionId(), response.getTransactionId());
        assertEquals("APPROVE", response.getDecision());
        assertEquals("Transaction accepted for processing", response.getMessage());
        assertEquals(0, response.getRuleScore());
        assertTrue(response.getTriggeredRules().isEmpty());
        assertTrue(response.getReasons().isEmpty());
        assertTrue(response.getMlAvailable());
        assertEquals(0.001, response.getFraudProbability());
        assertEquals("low", response.getMlBand());

        verify(transactionRepository).acquireAccountAdvisoryLock(sampleRequest.getAccountId());
        verify(transactionRepository).saveAndFlush(any(Transaction.class));
        verify(transactionFeatureRepository).saveAndFlush(any(TransactionFeature.class));

        // APPROVE does not create an alerts row
        verify(alertRepository, never()).saveAndFlush(any(Alert.class));
        // No ML outage -> no audit row
        verify(auditLogRepository, never()).saveAndFlush(any(AuditLog.class));
    }

    @Test
    @DisplayName("createTransaction with BLOCK decision creates Alert row with correct fields")
    void testCreateTransactionBlockCreatesAlertRow() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        UUID generatedId = UUID.randomUUID();
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(generatedId);
            return t;
        });

        BehavioralFeatureResult featureResult = new BehavioralFeatureResult(
                5, 10, new BigDecimal("100.00"), new BigDecimal("15.0000"), 30L, true, true, (short) 2, true, true
        );
        when(behavioralFeatureService.calculateFeatures(any(Transaction.class))).thenReturn(featureResult);

        RuleEvaluation ruleEvaluation = new RuleEvaluation(55, Collections.singletonList("HIGH_AMOUNT"), Collections.singletonList("Ratio high"));
        when(fraudRulesEngine.evaluate(any(Transaction.class), any(BehavioralFeatureResult.class))).thenReturn(ruleEvaluation);

        MLPredictionResponse mlResponse = new MLPredictionResponse(0.92, "xgboost-v1", 0.18, 0.56, "block", null, 15.0);
        when(mlServiceClient.predict(any(MLPredictionRequest.class))).thenReturn(Optional.of(mlResponse));

        HybridDecisionResult decisionResult = new HybridDecisionResult(
                Decision.BLOCK,
                RuleTier.HIGH,
                55,
                Collections.singletonList("HIGH_AMOUNT"),
                Collections.singletonList("Ratio high"),
                true,
                0.92,
                "block",
                null,
                0.92
        );
        when(hybridDecisionEngine.evaluate(any(RuleEvaluation.class), any())).thenReturn(decisionResult);

        TransactionResponse response = transactionService.createTransaction(sampleRequest);

        assertNotNull(response);
        assertEquals("BLOCK", response.getDecision());
        assertTrue(response.getMlAvailable());
        assertEquals(0.92, response.getFraudProbability());
        assertEquals("block", response.getMlBand());

        // Verify alert was created and saved
        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).saveAndFlush(alertCaptor.capture());
        Alert savedAlert = alertCaptor.getValue();

        assertEquals(generatedId, savedAlert.getTransactionId());
        assertEquals(Decision.BLOCK, savedAlert.getDecision());
        assertEquals(SeverityLevel.CRITICAL, savedAlert.getSeverity()); // BLOCK + mlAvailable + ruleTier HIGH -> CRITICAL
        assertEquals(AlertStatus.OPEN, savedAlert.getStatus());
        assertEquals(0, new BigDecimal("0.9200").compareTo(savedAlert.getFraudProbability()));
        assertEquals(0, new BigDecimal("55.00").compareTo(savedAlert.getRuleScore())); // raw integer 55.00
        assertEquals(0, new BigDecimal("0.9200").compareTo(savedAlert.getFinalScore()));
        assertNotNull(savedAlert.getExplanation());
        assertEquals(55, savedAlert.getExplanation().get("ruleScore"));
        assertEquals("HIGH", savedAlert.getExplanation().get("ruleTier"));

        // No ML outage -> no audit row
        verify(auditLogRepository, never()).saveAndFlush(any(AuditLog.class));
    }

    @Test
    @DisplayName("createTransaction with REVIEW decision creates Alert row with LOW or MEDIUM severity")
    void testCreateTransactionReviewCreatesAlertRow() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        UUID generatedId = UUID.randomUUID();
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(generatedId);
            return t;
        });

        BehavioralFeatureResult featureResult = new BehavioralFeatureResult(
                1, 2, new BigDecimal("100.00"), new BigDecimal("2.0000"), 100L, false, false, (short) 14, false, true
        );
        when(behavioralFeatureService.calculateFeatures(any(Transaction.class))).thenReturn(featureResult);

        RuleEvaluation ruleEvaluation = new RuleEvaluation(35, Collections.singletonList("VELOCITY"), Collections.singletonList("velocity"));
        when(fraudRulesEngine.evaluate(any(Transaction.class), any(BehavioralFeatureResult.class))).thenReturn(ruleEvaluation);

        MLPredictionResponse mlResponse = new MLPredictionResponse(0.25, "xgboost-v1", 0.18, 0.56, "review", null, 12.0);
        when(mlServiceClient.predict(any(MLPredictionRequest.class))).thenReturn(Optional.of(mlResponse));

        HybridDecisionResult decisionResult = new HybridDecisionResult(
                Decision.REVIEW,
                RuleTier.MEDIUM,
                35,
                Collections.singletonList("VELOCITY"),
                Collections.singletonList("velocity"),
                true,
                0.25,
                "review",
                null,
                0.25
        );
        when(hybridDecisionEngine.evaluate(any(RuleEvaluation.class), any())).thenReturn(decisionResult);

        TransactionResponse response = transactionService.createTransaction(sampleRequest);

        assertNotNull(response);
        assertEquals("REVIEW", response.getDecision());

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).saveAndFlush(alertCaptor.capture());
        Alert savedAlert = alertCaptor.getValue();

        assertEquals(Decision.REVIEW, savedAlert.getDecision());
        assertEquals(SeverityLevel.LOW, savedAlert.getSeverity()); // REVIEW + ruleTier MEDIUM -> LOW
        assertEquals(AlertStatus.OPEN, savedAlert.getStatus());
        assertEquals(0, new BigDecimal("0.2500").compareTo(savedAlert.getFraudProbability()));
        assertEquals(0, new BigDecimal("35.00").compareTo(savedAlert.getRuleScore())); // raw integer 35.00
    }

    @Test
    @DisplayName("When MLServiceClient returns Optional.empty(), fallback matrix is used, mlAvailable=false, and audit_logs row is written")
    void testCreateTransactionMLUnavailableFallbackAndAuditLog() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        UUID generatedId = UUID.randomUUID();
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(generatedId);
            return t;
        });

        BehavioralFeatureResult featureResult = new BehavioralFeatureResult(
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, true, true, (short) 10, false, false
        );
        when(behavioralFeatureService.calculateFeatures(any(Transaction.class))).thenReturn(featureResult);

        RuleEvaluation ruleEvaluation = new RuleEvaluation(45, Collections.singletonList("VELOCITY"), Collections.singletonList("velocity alert"));
        when(fraudRulesEngine.evaluate(any(Transaction.class), any(BehavioralFeatureResult.class))).thenReturn(ruleEvaluation);

        // ML returns empty (outage / timeout)
        when(mlServiceClient.predict(any(MLPredictionRequest.class))).thenReturn(Optional.empty());
        when(mlServiceClient.getLastFailureReason()).thenReturn("timeout");

        // Fallback matrix for HIGH tier -> REVIEW
        HybridDecisionResult fallbackResult = new HybridDecisionResult(
                Decision.REVIEW,
                RuleTier.HIGH,
                45,
                Collections.singletonList("VELOCITY"),
                Collections.singletonList("velocity alert"),
                false,
                null,
                null,
                null,
                null
        );
        when(hybridDecisionEngine.evaluate(any(RuleEvaluation.class), eq(Optional.empty()))).thenReturn(fallbackResult);

        TransactionResponse response = transactionService.createTransaction(sampleRequest);

        assertNotNull(response);
        assertEquals("REVIEW", response.getDecision());
        assertFalse(response.getMlAvailable());
        assertNull(response.getFraudProbability());
        assertNull(response.getMlBand());

        // An alert is created because fallback decision is REVIEW
        ArgumentCaptor<Alert> fallbackAlertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).saveAndFlush(fallbackAlertCaptor.capture());
        Alert fallbackAlert = fallbackAlertCaptor.getValue();
        assertEquals(SeverityLevel.MEDIUM, fallbackAlert.getSeverity()); // REVIEW + ruleTier HIGH -> MEDIUM
        assertEquals(false, fallbackAlert.getExplanation().get("mlAvailable"));
        assertNull(fallbackAlert.getExplanation().get("shapReasons"));
        assertNull(fallbackAlert.getExplanation().get("fraudProbability"));
        assertNull(fallbackAlert.getExplanation().get("mlBand"));

        // Audit log row must be written
        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(auditCaptor.capture());
        AuditLog savedAudit = auditCaptor.getValue();

        assertEquals("ML_SERVICE_UNAVAILABLE", savedAudit.getAction());
        assertEquals("transaction", savedAudit.getEntityType());
        assertEquals(sampleRequest.getTransactionId(), savedAudit.getEntityId());
        assertNull(savedAudit.getUserId());
        assertNull(savedAudit.getIpAddress());
        assertNotNull(savedAudit.getDetails());
        assertEquals("timeout", savedAudit.getDetails().get("reason"));
        assertEquals("HIGH", savedAudit.getDetails().get("ruleTier"));
        assertEquals("REVIEW", savedAudit.getDetails().get("fallbackDecision"));
    }

    @Test
    @DisplayName("createTransaction throws DuplicateTransactionException when transactionId already exists in pre-check")
    void testCreateTransactionDuplicatePreCheck() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(true);

        assertThrows(DuplicateTransactionException.class, () -> transactionService.createTransaction(sampleRequest));
        verify(transactionRepository).acquireAccountAdvisoryLock(sampleRequest.getAccountId());
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(transactionFeatureRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("createTransaction catches DataIntegrityViolationException on race condition and throws DuplicateTransactionException")
    void testCreateTransactionRaceCondition() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation on transaction_id"));

        assertThrows(DuplicateTransactionException.class, () -> transactionService.createTransaction(sampleRequest));
        verify(transactionRepository).acquireAccountAdvisoryLock(sampleRequest.getAccountId());
        verify(transactionFeatureRepository, never()).saveAndFlush(any());
    }
}
