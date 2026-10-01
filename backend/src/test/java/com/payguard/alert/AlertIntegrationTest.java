package com.payguard.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.alert.dto.AlertDetailResponse;
import com.payguard.alert.dto.AlertSummaryResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AlertIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private String analystJwtToken;
    private String adminJwtToken;
    private Alert openReviewAlert;
    private Alert resolvedBlockAlert;
    private Transaction txn1;
    private Transaction txn2;

    @BeforeEach
    void setUp() {
        User analyst = new User();
        analyst.setId(UUID.randomUUID());
        analyst.setUsername("analyst_test_" + UUID.randomUUID().toString().substring(0, 8));
        analyst.setEmail(analyst.getUsername() + "@payguard.com");
        analyst.setRole(UserRole.ANALYST);
        analyst.setIsActive(true);
        analystJwtToken = jwtService.generateToken(analyst);

        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setUsername("admin_test_" + UUID.randomUUID().toString().substring(0, 8));
        admin.setEmail(admin.getUsername() + "@payguard.com");
        admin.setRole(UserRole.ADMIN);
        admin.setIsActive(true);
        adminJwtToken = jwtService.generateToken(admin);

        txn1 = new Transaction();
        txn1.setTransactionId("TXN-ALERT-TEST-" + UUID.randomUUID().toString().substring(0, 8));
        txn1.setAccountId("ACC-ALERT-001");
        txn1.setAmount(new BigDecimal("1500.50"));
        txn1.setCurrency("INR");
        txn1.setDeviceId("device-alert-1");
        txn1.setLocation("Mumbai");
        txn1.setMerchantType("electronics");
        txn1.setTransactionTimestamp(OffsetDateTime.now().minusMinutes(10));
        txn1.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        txn1 = transactionRepository.saveAndFlush(txn1);

        openReviewAlert = new Alert();
        openReviewAlert.setTransactionId(txn1.getId());
        openReviewAlert.setDecision(Decision.REVIEW);
        openReviewAlert.setSeverity(SeverityLevel.MEDIUM);
        openReviewAlert.setStatus(AlertStatus.OPEN);
        openReviewAlert.setFraudProbability(new BigDecimal("0.7500"));
        openReviewAlert.setRuleScore(new BigDecimal("45.00"));
        openReviewAlert.setFinalScore(new BigDecimal("0.6250"));
        openReviewAlert.setExplanation(Map.of("ruleTier", "HIGH", "triggeredRules", List.of("HighAmountRule")));
        openReviewAlert.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        openReviewAlert.setUpdatedAt(OffsetDateTime.now().minusMinutes(10));
        openReviewAlert = alertRepository.saveAndFlush(openReviewAlert);

        txn2 = new Transaction();
        txn2.setTransactionId("TXN-ALERT-TEST-" + UUID.randomUUID().toString().substring(0, 8));
        txn2.setAccountId("ACC-ALERT-002");
        txn2.setAmount(new BigDecimal("9999.00"));
        txn2.setCurrency("INR");
        txn2.setDeviceId("device-alert-2");
        txn2.setLocation("Delhi");
        txn2.setMerchantType("jewelry");
        txn2.setTransactionTimestamp(OffsetDateTime.now().minusMinutes(5));
        txn2.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        txn2 = transactionRepository.saveAndFlush(txn2);

        resolvedBlockAlert = new Alert();
        resolvedBlockAlert.setTransactionId(txn2.getId());
        resolvedBlockAlert.setDecision(Decision.BLOCK);
        resolvedBlockAlert.setSeverity(SeverityLevel.CRITICAL);
        resolvedBlockAlert.setStatus(AlertStatus.RESOLVED);
        resolvedBlockAlert.setFraudProbability(new BigDecimal("0.9800"));
        resolvedBlockAlert.setRuleScore(new BigDecimal("90.00"));
        resolvedBlockAlert.setFinalScore(new BigDecimal("0.9400"));
        resolvedBlockAlert.setExplanation(Map.of("ruleTier", "HIGH", "triggeredRules", List.of("VelocityRule", "HighAmountRule")));
        resolvedBlockAlert.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        resolvedBlockAlert.setUpdatedAt(OffsetDateTime.now().minusMinutes(5));
        resolvedBlockAlert = alertRepository.saveAndFlush(resolvedBlockAlert);
    }

    @Test
    @DisplayName("GET /api/v1/alerts returns paginated list with 200 for authenticated analyst")
    void testGetAlertsAuthenticatedAnalyst() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.content[0].id").isString())
                .andExpect(jsonPath("$.content[0].transactionId").isString())
                .andExpect(jsonPath("$.content[0].decision").isString())
                .andExpect(jsonPath("$.content[0].status").isString())
                .andExpect(jsonPath("$.content[0].explanation").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/alerts returns 200 for authenticated admin")
    void testGetAlertsAuthenticatedAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + adminJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/alerts filtering by status works")
    void testGetAlertsFilterByStatus() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + analystJwtToken)
                        .param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].status").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("RESOLVED"))));
    }

    @Test
    @DisplayName("GET /api/v1/alerts filtering by decision works")
    void testGetAlertsFilterByDecision() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + analystJwtToken)
                        .param("decision", "BLOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].decision").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("BLOCK"))));
    }

    @Test
    @DisplayName("GET /api/v1/alerts pagination params work")
    void testGetAlertsPagination() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + analystJwtToken)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/alerts unauthenticated returns 401 Unauthorized")
    void testGetAlertsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("GET /api/v1/alerts/{id} returns 200 with full detail including explanation JSONB and transaction fields")
    void testGetAlertByIdSuccess() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/" + openReviewAlert.getId())
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(openReviewAlert.getId().toString()))
                .andExpect(jsonPath("$.transactionId").value(txn1.getTransactionId()))
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.severity").value("MEDIUM"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.fraudProbability").value(0.75))
                .andExpect(jsonPath("$.ruleScore").value(45.0))
                .andExpect(jsonPath("$.finalScore").value(0.625))
                .andExpect(jsonPath("$.explanation.ruleTier").value("HIGH"))
                .andExpect(jsonPath("$.amount").value(1500.50))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.deviceId").value("device-alert-1"))
                .andExpect(jsonPath("$.location").value("Mumbai"))
                .andExpect(jsonPath("$.merchantType").value("electronics"))
                .andExpect(jsonPath("$.transactionTimestamp").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/alerts/{id} returns 404 for non-existent id")
    void testGetAlertByIdNotFound() throws Exception {
        UUID randomId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/alerts/" + randomId)
                        .header("Authorization", "Bearer " + analystJwtToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("GET /api/v1/alerts/{id} unauthenticated returns 401 Unauthorized")
    void testGetAlertByIdUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/" + openReviewAlert.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Alert endpoints reject valid JWT with unauthorized role (VIEWER) with 403 Forbidden")
    void testAlertEndpointsRejectViewerRole() throws Exception {
        String viewerToken = jwtService.generateToken("unauthorized_user", "VIEWER");

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/alerts/" + openReviewAlert.getId())
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }
}
