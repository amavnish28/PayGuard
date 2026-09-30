package com.payguard.ml;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import com.payguard.decision.Decision;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import com.payguard.transaction.TransactionService;
import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.security.JwtService;
import com.payguard.user.User;
import com.payguard.user.UserRole;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("requires-ml-service")
class ManualVerificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("Scenario 1: Low-risk transaction against live FastAPI")
    void testScenario1LowRisk() {
        String accountId = "ACC-MANUAL-LOW-" + UUID.randomUUID().toString().substring(0, 8);
        String txnId = "TXN-MANUAL-LOW-" + UUID.randomUUID().toString().substring(0, 8);

        TransactionRequest req = new TransactionRequest(
                txnId,
                accountId,
                new BigDecimal("150.00"),
                "INR",
                "DEV-LOW-01",
                "Mumbai",
                "FOOD",
                OffsetDateTime.parse("2026-09-30T14:30:00+05:30")
        );

        TransactionResponse resp = transactionService.createTransaction(req);
        System.out.println("=== SCENARIO 1 RESULT ===");
        System.out.println("Transaction ID: " + resp.getTransactionId());
        System.out.println("Decision: " + resp.getDecision());
        System.out.println("ML Available: " + resp.isMlAvailable());
        System.out.println("ML Band: " + resp.getMlBand());
        System.out.println("Fraud Probability: " + resp.getFraudProbability());

        assertNotNull(resp);
        assertEquals("APPROVE", resp.getDecision());
        assertTrue(resp.isMlAvailable());
        assertEquals("low", resp.getMlBand());
        assertNotNull(resp.getFraudProbability());
        assertTrue(resp.getFraudProbability() < 0.30);

        // Verify NO alert in database
        Transaction txn = transactionRepository.findByTransactionId(txnId).orElseThrow();
        Optional<Alert> alertOpt = alertRepository.findByTransactionId(txn.getId());
        assertTrue(alertOpt.isEmpty(), "No alert row should exist for low-risk approved transaction");
        System.out.println("Scenario 1 verified: No alert row created in PostgreSQL.");
    }

    @Test
    @DisplayName("Scenario 2: High-risk burst/extreme amount transaction against live FastAPI")
    void testScenario2HighRisk() {
        String accountId = "ACC-MANUAL-HIGH-" + UUID.randomUUID().toString().substring(0, 8);
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-09-30T02:00:00+05:30"); // odd hour

        // Seed 4 prior transactions to establish average of 100.00
        for (int i = 1; i <= 4; i++) {
            String priorTxnId = "TXN-MANUAL-SEED-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);
            TransactionRequest priorReq = new TransactionRequest(
                    priorTxnId,
                    accountId,
                    new BigDecimal("100.00"),
                    "INR",
                    "DEV-HIGH-01",
                    "Delhi",
                    "RETAIL",
                    baseTime.plusMinutes(i)
            );
            transactionService.createTransaction(priorReq);
        }

        // Target high-risk transaction: extreme amount ratio (95,000 / 100 = 950x), odd hour 02:10, burst
        String targetTxnId = "TXN-MANUAL-HIGH-TARGET-" + UUID.randomUUID().toString().substring(0, 8);
        TransactionRequest targetReq = new TransactionRequest(
                targetTxnId,
                accountId,
                new BigDecimal("95000.00"),
                "INR",
                "DEV-HIGH-NEW",
                "Kolkata",
                "JEWELRY",
                baseTime.plusMinutes(10)
        );

        TransactionResponse resp = transactionService.createTransaction(targetReq);
        System.out.println("=== SCENARIO 2 RESULT ===");
        System.out.println("Transaction ID: " + resp.getTransactionId());
        System.out.println("Decision: " + resp.getDecision());
        System.out.println("ML Available: " + resp.isMlAvailable());
        System.out.println("ML Band: " + resp.getMlBand());
        System.out.println("Fraud Probability: " + resp.getFraudProbability());

        assertNotNull(resp);
        assertEquals("BLOCK", resp.getDecision());
        assertTrue(resp.isMlAvailable());
        assertEquals("block", resp.getMlBand());
        assertNotNull(resp.getFraudProbability());
        assertTrue(resp.getFraudProbability() >= 0.85);

        // Verify alert row in database
        Transaction txn = transactionRepository.findByTransactionId(targetTxnId).orElseThrow();
        Alert alert = alertRepository.findByTransactionId(txn.getId())
                .orElseThrow(() -> new AssertionError("Alert row MUST exist for high-risk blocked transaction"));

        System.out.println("=== SCENARIO 2 ALERT ROW ===");
        System.out.println("Alert ID: " + alert.getId());
        System.out.println("Transaction PK: " + alert.getTransactionId());
        System.out.println("Fraud Probability: " + alert.getFraudProbability());
        System.out.println("Rule Score: " + alert.getRuleScore());
        System.out.println("Final Score: " + alert.getFinalScore());
        System.out.println("Decision: " + alert.getDecision());
        System.out.println("Severity: " + alert.getSeverity());
        System.out.println("Status: " + alert.getStatus());
        System.out.println("Explanation: " + alert.getExplanation());

        assertEquals(Decision.BLOCK, alert.getDecision());
        assertNotNull(alert.getExplanation());
        assertTrue(alert.getExplanation().containsKey("shapReasons"), "Explanation must contain shapReasons");
        assertTrue(alert.getExplanation().containsKey("triggeredRules"), "Explanation must contain triggeredRules");
    }

    @Test
    @DisplayName("Scenario 4: Simulated ML outage against stopped FastAPI")
    void testScenario4Outage() {
        String accountId = "ACC-MANUAL-OUTAGE-" + UUID.randomUUID().toString().substring(0, 8);
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-09-30T10:00:00+05:30");

        // Seed 4 prior transactions to trigger VELOCITY on the 5th
        for (int i = 0; i < 4; i++) {
            String priorTxnId = "TXN-MANUAL-OUTAGE-SEED-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);
            TransactionRequest priorReq = new TransactionRequest(
                    priorTxnId,
                    accountId,
                    new BigDecimal("500.00"),
                    "INR",
                    "DEV-OUTAGE-01",
                    "Delhi",
                    "RETAIL",
                    baseTime.plusSeconds(i * 10)
            );
            transactionService.createTransaction(priorReq);
        }

        // 5th transaction triggers VELOCITY (+30) -> RuleTier = MEDIUM
        String targetTxnId = "TXN-MANUAL-OUTAGE-TARGET-" + UUID.randomUUID().toString().substring(0, 8);
        TransactionRequest targetReq = new TransactionRequest(
                targetTxnId,
                accountId,
                new BigDecimal("500.00"),
                "INR",
                "DEV-OUTAGE-01",
                "Delhi",
                "RETAIL",
                baseTime.plusSeconds(40)
        );

        TransactionResponse resp = transactionService.createTransaction(targetReq);
        System.out.println("=== SCENARIO 4 RESULT ===");
        System.out.println("Transaction ID: " + resp.getTransactionId());
        System.out.println("Decision: " + resp.getDecision());
        System.out.println("ML Available: " + resp.isMlAvailable());
        System.out.println("ML Band: " + resp.getMlBand());
        System.out.println("Fraud Probability: " + resp.getFraudProbability());

        assertNotNull(resp);
        assertEquals("REVIEW", resp.getDecision(), "Fallback matrix must map MEDIUM tier to REVIEW when ML fails");
        assertFalse(resp.isMlAvailable(), "mlAvailable must be false");
        assertNull(resp.getMlBand(), "mlBand must be null");
        assertNull(resp.getFraudProbability(), "fraudProbability must be null");

        // Verify audit_log row created for ML_SERVICE_UNAVAILABLE
        List<AuditLog> auditLogs = auditLogRepository.findAll().stream()
                .filter(a -> "ML_SERVICE_UNAVAILABLE".equals(a.getAction()) && targetTxnId.equals(a.getEntityId()))
                .toList();
        assertFalse(auditLogs.isEmpty(), "Audit log must be created for ML outage");
        AuditLog auditLog = auditLogs.getFirst();
        System.out.println("=== SCENARIO 4 AUDIT LOG ROW ===");
        System.out.println("Audit Log ID: " + auditLog.getId());
        System.out.println("Action: " + auditLog.getAction());
        System.out.println("Entity Type: " + auditLog.getEntityType());
        System.out.println("Entity ID: " + auditLog.getEntityId());
        System.out.println("Details: " + auditLog.getDetails());

        assertEquals("ML_SERVICE_UNAVAILABLE", auditLog.getAction());
        assertNotNull(auditLog.getDetails());
        assertTrue(auditLog.getDetails().containsKey("reason"));
    }

    @Test
    @DisplayName("Verify Widen rule_score: VELOCITY only (rule score 30) through API")
    void testVelocityOnlyVerification() throws Exception {
        User testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("analyst_" + UUID.randomUUID().toString().substring(0, 8));
        testUser.setEmail(testUser.getUsername() + "@payguard.com");
        testUser.setPasswordHash("$2a$10$dummyHash");
        testUser.setRole(UserRole.ANALYST);
        testUser.setIsActive(true);
        String token = jwtService.generateToken(testUser);

        String accountId = "ACC-WIDEN-" + UUID.randomUUID().toString().substring(0, 8);
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-09-30T14:00:00+05:30");

        // Seed 4 prior transactions
        for (int i = 0; i < 4; i++) {
            String priorTxnId = "TXN-WIDEN-SEED-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);
            TransactionRequest req = new TransactionRequest(
                    priorTxnId,
                    accountId,
                    new BigDecimal("200.00"),
                    "INR",
                    "DEV-WIDEN-01",
                    "Delhi",
                    "RETAIL",
                    baseTime.plusSeconds(i * 10)
            );
            mockMvc.perform(post("/api/transactions")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        // 5th transaction triggers VELOCITY only (+30) -> RuleTier.MEDIUM -> REVIEW
        String targetTxnId = "TXN-WIDEN-TARGET-" + UUID.randomUUID().toString().substring(0, 8);
        TransactionRequest targetReq = new TransactionRequest(
                targetTxnId,
                accountId,
                new BigDecimal("200.00"),
                "INR",
                "DEV-WIDEN-01",
                "Delhi",
                "RETAIL",
                baseTime.plusSeconds(40)
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(targetReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.ruleScore").value(30));

        System.out.println("=== VELOCITY VERIFICATION TRANSACTION SENT VIA API ===");
        System.out.println("Account ID: " + accountId);
        System.out.println("Target Transaction ID: " + targetTxnId);
    }
}
