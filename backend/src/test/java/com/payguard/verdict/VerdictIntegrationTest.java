package com.payguard.verdict;

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
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import com.payguard.verdict.dto.VerdictRequest;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VerdictIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AnalystVerdictRepository analystVerdictRepository;

    private User analystUser;
    private User adminUser;
    private User otherUser;
    private String analystToken;
    private String adminToken;

    private Alert openAlert1;
    private Alert openAlert2;

    @BeforeEach
    void setUp() {
        // Create test users in DB
        analystUser = new User();
        analystUser.setUsername("verdict_analyst_" + UUID.randomUUID().toString().substring(0, 8));
        analystUser.setEmail(analystUser.getUsername() + "@payguard.com");
        analystUser.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        analystUser.setRole(UserRole.ANALYST);
        analystUser.setIsActive(true);
        analystUser = userRepository.saveAndFlush(analystUser);
        analystToken = jwtService.generateToken(analystUser);

        adminUser = new User();
        adminUser.setUsername("verdict_admin_" + UUID.randomUUID().toString().substring(0, 8));
        adminUser.setEmail(adminUser.getUsername() + "@payguard.com");
        adminUser.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        adminUser.setRole(UserRole.ADMIN);
        adminUser.setIsActive(true);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateToken(adminUser);

        otherUser = new User();
        otherUser.setUsername("other_user_" + UUID.randomUUID().toString().substring(0, 8));
        otherUser.setEmail(otherUser.getUsername() + "@payguard.com");
        otherUser.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        otherUser.setRole(UserRole.ANALYST);
        otherUser.setIsActive(true);
        otherUser = userRepository.saveAndFlush(otherUser);

        // Create test transactions
        Transaction txn1 = new Transaction();
        txn1.setTransactionId("TXN-VERDICT-" + UUID.randomUUID().toString().substring(0, 8));
        txn1.setAccountId("ACC-VERDICT-01");
        txn1.setAmount(new BigDecimal("2500.00"));
        txn1.setCurrency("INR");
        txn1.setTransactionTimestamp(OffsetDateTime.now());
        txn1 = transactionRepository.saveAndFlush(txn1);

        Transaction txn2 = new Transaction();
        txn2.setTransactionId("TXN-VERDICT-" + UUID.randomUUID().toString().substring(0, 8));
        txn2.setAccountId("ACC-VERDICT-02");
        txn2.setAmount(new BigDecimal("7500.00"));
        txn2.setCurrency("INR");
        txn2.setTransactionTimestamp(OffsetDateTime.now());
        txn2 = transactionRepository.saveAndFlush(txn2);

        // Create test alerts
        openAlert1 = new Alert();
        openAlert1.setTransactionId(txn1.getId());
        openAlert1.setDecision(Decision.REVIEW);
        openAlert1.setSeverity(SeverityLevel.HIGH);
        openAlert1.setStatus(AlertStatus.OPEN);
        openAlert1 = alertRepository.saveAndFlush(openAlert1);

        openAlert2 = new Alert();
        openAlert2.setTransactionId(txn2.getId());
        openAlert2.setDecision(Decision.BLOCK);
        openAlert2.setSeverity(SeverityLevel.CRITICAL);
        openAlert2.setStatus(AlertStatus.OPEN);
        openAlert2 = alertRepository.saveAndFlush(openAlert2);
    }

    @Test
    @DisplayName("Valid verdict submission (FRAUD) -> 201, verdict persisted, alert status becomes RESOLVED")
    void submitVerdictFraud_Success() throws Exception {
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, "Confirmed card fraud scheme");

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.alertId", is(openAlert1.getId().toString())))
                .andExpect(jsonPath("$.verdict", is("FRAUD")))
                .andExpect(jsonPath("$.comment", is("Confirmed card fraud scheme")))
                .andExpect(jsonPath("$.analystUsername", is(analystUser.getUsername())))
                .andExpect(jsonPath("$.createdAt", notNullValue()));

        // Confirm database state
        AnalystVerdict persisted = analystVerdictRepository.findByAlertId(openAlert1.getId()).orElseThrow();
        assertThat(persisted.getVerdict()).isEqualTo(VerdictType.FRAUD);
        assertThat(persisted.getComment()).isEqualTo("Confirmed card fraud scheme");
        assertThat(persisted.getAnalystId()).isEqualTo(analystUser.getId());

        Alert updatedAlert = alertRepository.findById(openAlert1.getId()).orElseThrow();
        assertThat(updatedAlert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
    }

    @Test
    @DisplayName("Valid verdict submission (LEGITIMATE) -> 201, alert status becomes RESOLVED")
    void submitVerdictLegitimate_Success() throws Exception {
        VerdictRequest request = new VerdictRequest(VerdictType.LEGITIMATE, "Customer verified purchase");

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert2.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict", is("LEGITIMATE")))
                .andExpect(jsonPath("$.alertId", is(openAlert2.getId().toString())));

        Alert updatedAlert = alertRepository.findById(openAlert2.getId()).orElseThrow();
        assertThat(updatedAlert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
    }

    @Test
    @DisplayName("Second verdict submission for the same alert -> 409 Conflict, original verdict unchanged")
    void submitDuplicateVerdict_Returns409() throws Exception {
        VerdictRequest firstRequest = new VerdictRequest(VerdictType.FRAUD, "Initial verdict");
        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isCreated());

        VerdictRequest secondRequest = new VerdictRequest(VerdictType.LEGITIMATE, "Conflicting second verdict");
        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.message", is("Verdict already exists for this alert")));

        // Confirm original verdict is untouched
        AnalystVerdict persisted = analystVerdictRepository.findByAlertId(openAlert1.getId()).orElseThrow();
        assertThat(persisted.getVerdict()).isEqualTo(VerdictType.FRAUD);
        assertThat(persisted.getComment()).isEqualTo("Initial verdict");
    }

    @Test
    @DisplayName("Verdict for a non-existent alert id -> 404 Not Found")
    void submitVerdict_NonExistentAlert_Returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, "Alert does not exist");

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", nonExistentId)
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.message", is("Alert not found with ID: " + nonExistentId)));
    }

    @Test
    @DisplayName("Missing verdict field -> 400 Bad Request")
    void submitVerdict_MissingVerdict_Returns400() throws Exception {
        Map<String, Object> body = Map.of("comment", "Missing verdict field");

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("Invalid verdict value (not FRAUD or LEGITIMATE) -> 400 Bad Request")
    void submitVerdict_InvalidVerdictValue_Returns400() throws Exception {
        Map<String, Object> body = Map.of("verdict", "INVALID_VALUE", "comment", "Invalid enum value");

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("Comment exceeding max length (2000 chars) -> 400 Bad Request")
    void submitVerdict_CommentTooLong_Returns400() throws Exception {
        String longComment = "A".repeat(2001);
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, longComment);

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("Unauthenticated request -> 401 Unauthorized")
    void submitVerdict_Unauthenticated_Returns401() throws Exception {
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, "No token provided");

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sending analystId/alertId in request body has NO effect; analyst_id correctly set to authenticated user")
    void submitVerdict_FabricatedAnalystIdIgnored() throws Exception {
        UUID fabricatedAnalystId = otherUser.getId();
        UUID fabricatedAlertId = UUID.randomUUID();

        // Inject fabricated fields into JSON body
        Map<String, Object> payloadWithImpersonation = Map.of(
                "verdict", "FRAUD",
                "comment", "Attempting client-side analystId spoofing",
                "analystId", fabricatedAnalystId.toString(),
                "alertId", fabricatedAlertId.toString()
        );

        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payloadWithImpersonation)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alertId", is(openAlert1.getId().toString())))
                .andExpect(jsonPath("$.analystUsername", is(analystUser.getUsername())));

        // Verify in database directly that analyst_id is the authenticated user, NOT otherUser
        AnalystVerdict saved = analystVerdictRepository.findByAlertId(openAlert1.getId()).orElseThrow();
        assertThat(saved.getAnalystId()).isEqualTo(analystUser.getId());
        assertThat(saved.getAnalystId()).isNotEqualTo(fabricatedAnalystId);
        assertThat(saved.getAlertId()).isEqualTo(openAlert1.getId());
    }

    @Test
    @DisplayName("Alert status transitions correctly from OPEN to RESOLVED (query before and after)")
    void submitVerdict_AlertStatusTransitionsFromOpenToResolved() throws Exception {
        // Query before
        Alert before = alertRepository.findById(openAlert1.getId()).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(AlertStatus.OPEN);

        VerdictRequest request = new VerdictRequest(VerdictType.LEGITIMATE, "Clean status transition test");
        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Query after
        Alert after = alertRepository.findById(openAlert1.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AlertStatus.RESOLVED);
    }

    @Test
    @DisplayName("Both ANALYST and ADMIN roles can successfully submit a verdict")
    void submitVerdict_BothAnalystAndAdminRolesSupported() throws Exception {
        // ANALYST submits on openAlert1
        VerdictRequest request1 = new VerdictRequest(VerdictType.FRAUD, "Analyst submitted");
        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert1.getId())
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analystUsername", is(analystUser.getUsername())));

        // ADMIN submits on openAlert2
        VerdictRequest request2 = new VerdictRequest(VerdictType.LEGITIMATE, "Admin submitted");
        mockMvc.perform(post("/api/v1/alerts/{alertId}/verdict", openAlert2.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analystUsername", is(adminUser.getUsername())));

        assertThat(alertRepository.findById(openAlert1.getId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(alertRepository.findById(openAlert2.getId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.RESOLVED);
    }
}
