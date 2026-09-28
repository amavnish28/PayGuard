package com.payguard.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.security.JwtService;
import com.payguard.transaction.dto.TransactionRequest;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TransactionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TransactionRepository transactionRepository;

    private String validJwtToken;

    @BeforeEach
    void setUp() {
        User testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("analyst_" + UUID.randomUUID().toString().substring(0, 8));
        testUser.setEmail(testUser.getUsername() + "@payguard.com");
        testUser.setPasswordHash("$2a$10$dummyHash");
        testUser.setRole(UserRole.ANALYST);
        testUser.setIsActive(true);

        validJwtToken = jwtService.generateToken(testUser);
    }

    private TransactionRequest createValidRequest(String txnId) {
        return new TransactionRequest(
                txnId,
                "ACC-1001",
                new BigDecimal("1500.00"),
                "INR",
                "DEV-999",
                "Delhi",
                "RETAIL",
                OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );
    }

    @Test
    @DisplayName("1. Valid authenticated transaction returns HTTP 201 Created and stub APPROVE decision")
    void testValidAuthenticatedTransaction() throws Exception {
        String txnId = "TXN-" + UUID.randomUUID();
        TransactionRequest request = createValidRequest(txnId);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(txnId))
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.message").value("Transaction accepted for processing"));
    }

    @Test
    @DisplayName("2. Missing JWT returns HTTP 401 Unauthorized")
    void testMissingJwtReturns401() throws Exception {
        String txnId = "TXN-" + UUID.randomUUID();
        TransactionRequest request = createValidRequest(txnId);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("3. Invalid JWT returns HTTP 401 Unauthorized")
    void testInvalidJwtReturns401() throws Exception {
        String txnId = "TXN-" + UUID.randomUUID();
        TransactionRequest request = createValidRequest(txnId);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer invalid-tampered-token-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("4. Invalid amount returns HTTP 400 Bad Request")
    void testInvalidAmountReturns400() throws Exception {
        // Negative amount
        String jsonNegative = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": -50.00,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonNegative))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Zero amount
        String jsonZero = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": 0.00,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonZero))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Exceeds fraction digits (3 decimal places)
        String jsonExcessFraction = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": 100.125,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonExcessFraction))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Non-numeric amount string
        String jsonMalformedAmount = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": "not-a-number",
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMalformedAmount))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("5. Missing required fields return HTTP 400 Bad Request")
    void testMissingRequiredFieldsReturns400() throws Exception {
        // Missing accountId
        String jsonMissingAccount = """
                {
                    "transactionId": "TXN-%s",
                    "amount": 100.00,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMissingAccount))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Missing transactionTimestamp
        String jsonMissingTimestamp = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": 100.00,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMissingTimestamp))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("6. Invalid currency returns HTTP 400 Bad Request")
    void testInvalidCurrencyReturns400() throws Exception {
        // Lowercase
        String jsonLower = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": 100.00,
                    "currency": "inr",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonLower))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Length not 3
        String jsonLong = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": 100.00,
                    "currency": "USDD",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("7. Oversized field (>100 chars) returns HTTP 400 Bad Request")
    void testOversizedFieldReturns400() throws Exception {
        String longId = "A".repeat(101);
        String jsonOversized = """
                {
                    "transactionId": "%s",
                    "accountId": "ACC-01",
                    "amount": 100.00,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30"
                }
                """.formatted(longId);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonOversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("8. Invalid timestamp returns HTTP 400 Bad Request")
    void testInvalidTimestampReturns400() throws Exception {
        String jsonBadTimestamp = """
                {
                    "transactionId": "TXN-%s",
                    "accountId": "ACC-01",
                    "amount": 100.00,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Delhi",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "invalid-timestamp-value"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBadTimestamp))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("9. Duplicate transaction_id returns HTTP 409 Conflict")
    void testDuplicateTransactionIdReturns409() throws Exception {
        String txnId = "TXN-" + UUID.randomUUID();
        TransactionRequest request = createValidRequest(txnId);

        // First attempt succeeds
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Duplicate attempt returns 409 Conflict
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    @DisplayName("10. Transaction is actually saved to PostgreSQL")
    void testTransactionActuallySavedToDatabase() throws Exception {
        String txnId = "TXN-" + UUID.randomUUID();
        TransactionRequest request = createValidRequest(txnId);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        Optional<Transaction> savedOpt = transactionRepository.findByTransactionId(txnId);
        assertTrue(savedOpt.isPresent(), "Transaction must exist in PostgreSQL");
        Transaction saved = savedOpt.get();

        assertNotNull(saved.getId(), "Generated UUID id must be populated");
        assertEquals(txnId, saved.getTransactionId());
        assertEquals("ACC-1001", saved.getAccountId());
        assertEquals(0, new BigDecimal("1500.00").compareTo(saved.getAmount()));
        assertEquals("INR", saved.getCurrency());
        assertEquals("DEV-999", saved.getDeviceId());
        assertEquals("Delhi", saved.getLocation());
        assertEquals("RETAIL", saved.getMerchantType());
        assertEquals(OffsetDateTime.parse("2026-09-28T10:00:00+05:30").toInstant(), saved.getTransactionTimestamp().toInstant());
        assertNull(saved.getIsFraud(), "is_fraud must be null");
        assertNotNull(saved.getCreatedAt(), "created_at must be populated");
    }

    @Test
    @DisplayName("11. Client attempts isFraud=true: client cannot control field, saved isFraud remains null")
    void testClientAttemptsIsFraudTrueRemainsNull() throws Exception {
        String txnId = "TXN-FRAUD-ATTEMPT-" + UUID.randomUUID();
        String jsonWithFraud = """
                {
                    "transactionId": "%s",
                    "accountId": "ACC-FRAUD-01",
                    "amount": 2500.00,
                    "currency": "INR",
                    "deviceId": "DEV-01",
                    "location": "Mumbai",
                    "merchantType": "RETAIL",
                    "transactionTimestamp": "2026-09-28T10:00:00+05:30",
                    "isFraud": true
                }
                """.formatted(txnId);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithFraud))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("APPROVE"));

        // Verify saved transaction in database has isFraud = null
        Optional<Transaction> savedOpt = transactionRepository.findByTransactionId(txnId);
        assertTrue(savedOpt.isPresent());
        assertNull(savedOpt.get().getIsFraud(), "Database is_fraud column must remain NULL");

        // Verify TransactionRequest DTO does NOT declare an isFraud field
        boolean hasIsFraudField = Arrays.stream(TransactionRequest.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equalsIgnoreCase("isFraud"));
        assertFalse(hasIsFraudField, "TransactionRequest DTO must not expose isFraud as an accepted application field");
    }
}
