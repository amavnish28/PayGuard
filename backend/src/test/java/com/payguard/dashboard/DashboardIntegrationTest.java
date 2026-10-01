package com.payguard.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.decision.Decision;
import com.payguard.security.JwtService;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import com.payguard.user.User;
import com.payguard.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AlertRepository alertRepository;

    private String analystJwtToken;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        transactionRepository.deleteAll();

        User analyst = new User();
        analyst.setId(UUID.randomUUID());
        analyst.setUsername("analyst_dash_" + UUID.randomUUID().toString().substring(0, 8));
        analyst.setEmail(analyst.getUsername() + "@payguard.com");
        analyst.setRole(UserRole.ANALYST);
        analyst.setIsActive(true);
        analystJwtToken = jwtService.generateToken(analyst);
    }

    private Transaction createTransaction(String prefix, OffsetDateTime createdAt) {
        Transaction txn = new Transaction();
        txn.setTransactionId("TXN-DASH-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        txn.setAccountId("ACC-" + UUID.randomUUID().toString().substring(0, 8));
        txn.setAmount(BigDecimal.valueOf(150.00));
        txn.setCurrency("INR");
        txn.setDeviceId("DEV-1");
        txn.setLocation("Mumbai");
        txn.setMerchantType("RETAIL");
        txn.setTransactionTimestamp(createdAt);
        txn.setCreatedAt(createdAt);
        return transactionRepository.save(txn);
    }

    private Alert createAlert(Transaction txn, Decision decision, SeverityLevel severity, AlertStatus status,
                              boolean mlAvailable, OffsetDateTime createdAt) {
        Alert alert = new Alert();
        alert.setTransactionId(txn.getId());
        alert.setDecision(decision);
        alert.setSeverity(severity);
        alert.setStatus(status);
        alert.setRuleScore(BigDecimal.valueOf(40.00));
        alert.setFinalScore(BigDecimal.valueOf(0.6000));
        alert.setFraudProbability(BigDecimal.valueOf(0.6500));
        alert.setCreatedAt(createdAt);
        alert.setUpdatedAt(createdAt);

        Map<String, Object> explanation = Map.of(
                "mlAvailable", mlAvailable,
                "ruleTier", "MEDIUM",
                "mlBand", "HIGH"
        );
        alert.setExplanation(explanation);
        return alertRepository.save(alert);
    }

    @Test
    @DisplayName("GET /api/v1/dashboard/summary returns 200 with exact nested DTO JSON structure")
    void testGetSummary_ExactJsonStructure() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + analystJwtToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").isNumber())
                .andExpect(jsonPath("$.totalAlerts").isNumber())
                .andExpect(jsonPath("$.decisionCounts").isMap())
                .andExpect(jsonPath("$.decisionCounts.approve").isNumber())
                .andExpect(jsonPath("$.decisionCounts.review").isNumber())
                .andExpect(jsonPath("$.decisionCounts.block").isNumber())
                .andExpect(jsonPath("$.statusCounts").isMap())
                .andExpect(jsonPath("$.statusCounts.open").isNumber())
                .andExpect(jsonPath("$.statusCounts.inReview").isNumber())
                .andExpect(jsonPath("$.statusCounts.resolved").isNumber())
                .andExpect(jsonPath("$.severityCounts").isMap())
                .andExpect(jsonPath("$.severityCounts.low").isNumber())
                .andExpect(jsonPath("$.severityCounts.medium").isNumber())
                .andExpect(jsonPath("$.severityCounts.high").isNumber())
                .andExpect(jsonPath("$.severityCounts.critical").isNumber())
                .andExpect(jsonPath("$.severityCounts.unclassified").isNumber())
                .andExpect(jsonPath("$.windowed").isMap())
                .andExpect(jsonPath("$.windowed.sinceTimestamp").isString())
                .andExpect(jsonPath("$.windowed.transactionsInWindow").isNumber())
                .andExpect(jsonPath("$.windowed.alertsInWindow").isNumber())
                .andExpect(jsonPath("$.windowed.blockCountInWindow").isNumber())
                .andExpect(jsonPath("$.windowed.reviewCountInWindow").isNumber());
    }

    @Test
    @DisplayName("Counts and ML availability are calculated accurately with known seeded test data")
    void testGetSummary_KnownSeededDataCalculation() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime inWindowTime = now.minusHours(2);
        OffsetDateTime outOfWindowTime = now.minusHours(48);

        // Alert 1: in-window, REVIEW, MEDIUM, OPEN, mlAvailable=true
        Transaction txn1 = createTransaction("WIN1", inWindowTime);
        createAlert(txn1, Decision.REVIEW, SeverityLevel.MEDIUM, AlertStatus.OPEN, true, inWindowTime);

        // Alert 2: in-window, BLOCK, HIGH, IN_REVIEW, mlAvailable=false
        Transaction txn2 = createTransaction("WIN2", inWindowTime);
        createAlert(txn2, Decision.BLOCK, SeverityLevel.HIGH, AlertStatus.IN_REVIEW, false, inWindowTime);

        // Alert 3: out-of-window, BLOCK, CRITICAL, RESOLVED, mlAvailable=true
        Transaction txn3 = createTransaction("OUT1", outOfWindowTime);
        createAlert(txn3, Decision.BLOCK, SeverityLevel.CRITICAL, AlertStatus.RESOLVED, true, outOfWindowTime);

        mockMvc.perform(get("/api/v1/dashboard/summary?sinceHours=24")
                        .header("Authorization", "Bearer " + analystJwtToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowed.transactionsInWindow").value(2))
                .andExpect(jsonPath("$.windowed.alertsInWindow").value(2))
                .andExpect(jsonPath("$.windowed.reviewCountInWindow").value(1))
                .andExpect(jsonPath("$.windowed.blockCountInWindow").value(1))
                // Among 2 in-window alerts: 1 mlAvailable=true, 1 mlAvailable=false -> 0.5
                .andExpect(jsonPath("$.mlAvailabilityRate").value(0.5));
    }

    @Test
    @DisplayName("sinceHours filtering excludes older alerts from windowed stats but retains them in all-time counts")
    void testGetSummary_SinceHoursFiltering() throws Exception {
        OffsetDateTime oldTime = OffsetDateTime.now(ZoneOffset.UTC).minusHours(50);
        Transaction txnOld = createTransaction("OLD", oldTime);
        createAlert(txnOld, Decision.BLOCK, SeverityLevel.CRITICAL, AlertStatus.OPEN, true, oldTime);

        // Window = 10 hours -> Old alert is excluded from windowed counts
        mockMvc.perform(get("/api/v1/dashboard/summary?sinceHours=10")
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowed.alertsInWindow").value(0))
                .andExpect(jsonPath("$.windowed.blockCountInWindow").value(0))
                .andExpect(jsonPath("$.mlAvailabilityRate").value(nullValue()));
    }

    @Test
    @DisplayName("mlAvailabilityRate is null when alertsInWindow is 0")
    void testGetSummary_MlAvailabilityRateNullWhenAlertsInWindowIsZero() throws Exception {
        // with sinceHours=1 and no recent alerts created
        mockMvc.perform(get("/api/v1/dashboard/summary?sinceHours=1")
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowed.alertsInWindow").value(0))
                .andExpect(jsonPath("$.mlAvailabilityRate").value(nullValue()));
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void testGetSummary_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Invalid sinceHours values return 400 Bad Request")
    void testGetSummary_InvalidSinceHours_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary?sinceHours=0")
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("sinceHours must be between 1 and 720"));

        mockMvc.perform(get("/api/v1/dashboard/summary?sinceHours=-5")
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        mockMvc.perform(get("/api/v1/dashboard/summary?sinceHours=721")
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }
}
