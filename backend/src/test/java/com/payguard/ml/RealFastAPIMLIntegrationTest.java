package com.payguard.ml;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.decision.Decision;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import com.payguard.transaction.TransactionService;
import com.payguard.transaction.feature.TransactionFeatureRepository;
import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("requires-ml-service")
@SpringBootTest
class RealFastAPIMLIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionFeatureRepository transactionFeatureRepository;

    @Autowired
    private AlertRepository alertRepository;

    private final List<String> createdTransactionIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String txnId : createdTransactionIds) {
            try {
                Optional<Transaction> opt = transactionRepository.findByTransactionId(txnId);
                if (opt.isPresent()) {
                    Transaction t = opt.get();
                    alertRepository.findByTransactionId(t.getId()).ifPresent(alertRepository::delete);
                    transactionFeatureRepository.deleteByTransactionRefId(t.getId());
                    transactionRepository.delete(t);
                }
            } catch (Exception ignored) {
            }
        }
        createdTransactionIds.clear();
    }

    private TransactionRequest createRequest(String txnId, String accountId, BigDecimal amount,
                                             String deviceId, String location, OffsetDateTime timestamp) {
        createdTransactionIds.add(txnId);
        return new TransactionRequest(
                txnId,
                accountId,
                amount,
                "INR",
                deviceId,
                location,
                "RETAIL",
                timestamp
        );
    }

    @Test
    @DisplayName("End-to-end low-risk transaction with real FastAPI: APPROVE, mlAvailable=true, no alert row")
    void testEndToEndLowRiskTransactionApprovesWithoutAlert() {
        String accountId = "ACC-E2E-LOW-" + UUID.randomUUID();
        String txnId = "TXN-E2E-LOW-" + UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-09-30T14:00:00+05:30");

        TransactionRequest req = createRequest(txnId, accountId, new BigDecimal("25.00"), "DEV-NORMAL", "Delhi", timestamp);
        TransactionResponse response = transactionService.createTransaction(req);

        assertNotNull(response);
        assertEquals("APPROVE", response.getDecision());
        assertTrue(response.getMlAvailable(), "ML service must be available on port 8001");
        assertNotNull(response.getFraudProbability());
        assertTrue(response.getFraudProbability() < 0.10, "Low-risk transaction should have very low fraud probability");
        assertEquals("low", response.getMlBand());

        Transaction t = transactionRepository.findByTransactionId(txnId).orElseThrow();
        Optional<Alert> alertOpt = alertRepository.findByTransactionId(t.getId());
        assertTrue(alertOpt.isEmpty(), "APPROVE decision must NOT create an alert row");
    }

    @Test
    @DisplayName("End-to-end high-risk transaction with real FastAPI: BLOCK, mlAvailable=true, alert row exists with fraud_probability and SHAP reasons")
    void testEndToEndHighRiskTransactionBlocksWithAlertAndShap() {
        String accountId = "ACC-E2E-HIGH-" + UUID.randomUUID();
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-09-30T02:00:00+05:30"); // Odd hour 2 AM

        // 1. Establish account history with 5 rapid transactions in 2 minutes
        for (int i = 0; i < 5; i++) {
            String histId = "TXN-HIST-" + i + "-" + UUID.randomUUID();
            TransactionRequest histReq = createRequest(
                    histId,
                    accountId,
                    new BigDecimal("50.00"),
                    "DEV-ORIGINAL",
                    "Delhi",
                    baseTime.plusSeconds(i * 10)
            );
            transactionService.createTransaction(histReq);
        }

        // 2. High-risk transaction: huge amount ratio, new device, new location, odd hour, high velocity
        String highTxnId = "TXN-HIGH-" + UUID.randomUUID();
        TransactionRequest highReq = createRequest(
                highTxnId,
                accountId,
                new BigDecimal("50000.00"), // 1000x normal amount
                "DEV-NEW-SUSPECT",          // new device
                "Moscow",                   // new location
                baseTime.plusSeconds(70)    // within 2 minutes of prior txns
        );

        TransactionResponse response = transactionService.createTransaction(highReq);

        assertNotNull(response);
        assertEquals("BLOCK", response.getDecision(), "High-risk payload should trigger BLOCK hybrid decision");
        assertTrue(response.getMlAvailable());
        assertNotNull(response.getFraudProbability());
        assertTrue(response.getFraudProbability() >= 0.56667, "Fraud probability should exceed block threshold");
        assertEquals("block", response.getMlBand());

        // Verify alert table has row with explanation JSONB
        Transaction t = transactionRepository.findByTransactionId(highTxnId).orElseThrow();
        Alert alert = alertRepository.findByTransactionId(t.getId()).orElseThrow(
                () -> new AssertionError("Alert row must exist for BLOCK decision")
        );

        assertEquals(Decision.BLOCK, alert.getDecision());
        assertNotNull(alert.getFraudProbability());
        assertTrue(alert.getFraudProbability().doubleValue() >= 0.56);
        assertNotNull(alert.getExplanation());

        Map<String, Object> explanation = alert.getExplanation();
        assertTrue(explanation.containsKey("shapReasons"));
        Object shapReasonsObj = explanation.get("shapReasons");
        assertNotNull(shapReasonsObj, "shapReasons must be populated when probability >= review_threshold");
        assertTrue(shapReasonsObj instanceof List, "shapReasons must be a list");
        List<?> shapList = (List<?>) shapListCast(shapReasonsObj);
        assertEquals(5, shapList.size(), "Must have top 5 SHAP reasons");
    }

    @SuppressWarnings("unchecked")
    private List<?> shapListCast(Object obj) {
        return (List<?>) obj;
    }
}
