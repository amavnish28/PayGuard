package com.payguard.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.security.JwtService;
import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.transaction.feature.TransactionFeatureRepository;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BehavioralFeatureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionFeatureRepository transactionFeatureRepository;

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

    private TransactionRequest createRequest(String txnId, String accountId, BigDecimal amount,
                                            String deviceId, String location, OffsetDateTime timestamp) {
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
    @DisplayName("1. Cold Start: First transaction has new_device=true, new_location=true, but rules do NOT trigger (score = 0)")
    void testColdStart() throws Exception {
        String accountId = "ACC-COLD-" + UUID.randomUUID();
        String txnId = "TXN-COLD-" + UUID.randomUUID();
        TransactionRequest request = createRequest(
                txnId, accountId, new BigDecimal("1500.00"),
                "DEV-001", "Delhi", OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(txnId))
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.ruleScore").value(0))
                .andExpect(jsonPath("$.triggeredRules", hasSize(0)))
                .andExpect(jsonPath("$.reasons", hasSize(0)));

        Transaction savedTxn = transactionRepository.findByTransactionId(txnId).orElseThrow();
        TransactionFeature feature = transactionFeatureRepository.findByTransactionRefId(savedTxn.getId()).orElseThrow();

        assertEquals(0, feature.getTransactionsLast2Min());
        assertEquals(0, feature.getTransactionsLast1Hour());
        assertEquals(new BigDecimal("0.00"), feature.getAccountAvgAmount());
        assertEquals(new BigDecimal("0.0000"), feature.getAmountRatio());
        assertNull(feature.getTimeSincePreviousTransactionSeconds());
        assertTrue(feature.getNewDevice());
        assertTrue(feature.getNewLocation());
        assertEquals((short) 10, feature.getTransactionHour());
        assertFalse(feature.getOddHour());
        assertNotNull(feature.getId());
    }

    @Test
    @DisplayName("2. Subsequent transaction with same device and location has new_device=false, new_location=false")
    void testSubsequentSameDeviceAndLocation() throws Exception {
        String accountId = "ACC-SUB-" + UUID.randomUUID();
        String txn1Id = "TXN-SUB-1-" + UUID.randomUUID();
        String txn2Id = "TXN-SUB-2-" + UUID.randomUUID();

        // Transaction 1
        TransactionRequest req1 = createRequest(
                txn1Id, accountId, new BigDecimal("1000.00"),
                "DEV-SAME", "Mumbai", OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Transaction 2 (same device, same location, 2 minutes later)
        TransactionRequest req2 = createRequest(
                txn2Id, accountId, new BigDecimal("1000.00"),
                "DEV-SAME", "Mumbai", OffsetDateTime.parse("2026-09-28T10:02:00+05:30")
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleScore").value(0))
                .andExpect(jsonPath("$.triggeredRules", hasSize(0)));

        Transaction savedTxn2 = transactionRepository.findByTransactionId(txn2Id).orElseThrow();
        TransactionFeature feature2 = transactionFeatureRepository.findByTransactionRefId(savedTxn2.getId()).orElseThrow();

        assertFalse(feature2.getNewDevice());
        assertFalse(feature2.getNewLocation());
        assertEquals(120L, feature2.getTimeSincePreviousTransactionSeconds());
        assertEquals(new BigDecimal("1000.00"), feature2.getAccountAvgAmount());
        assertEquals(new BigDecimal("1.0000"), feature2.getAmountRatio());
    }

    @Test
    @DisplayName("3. Velocity: 4 previous transactions within 2 minutes triggers VELOCITY (+30)")
    void testVelocityTriggerAtFourPrevious() throws Exception {
        String accountId = "ACC-VEL-" + UUID.randomUUID();
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-09-28T10:00:00+05:30");

        // Create 4 prior transactions within 2 minutes
        for (int i = 0; i < 4; i++) {
            String priorTxnId = "TXN-VEL-PRIOR-" + i + "-" + UUID.randomUUID();
            TransactionRequest req = createRequest(
                    priorTxnId, accountId, new BigDecimal("500.00"),
                    "DEV-VEL", "Delhi", baseTime.plusSeconds(i * 10)
            );
            mockMvc.perform(post("/api/transactions")
                            .header("Authorization", "Bearer " + validJwtToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        // 5th transaction at baseTime + 50s
        String fifthTxnId = "TXN-VEL-FIFTH-" + UUID.randomUUID();
        TransactionRequest fifthReq = createRequest(
                fifthTxnId, accountId, new BigDecimal("500.00"),
                "DEV-VEL", "Delhi", baseTime.plusSeconds(50)
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fifthReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleScore").value(30))
                .andExpect(jsonPath("$.triggeredRules", contains("VELOCITY")))
                .andExpect(jsonPath("$.reasons", contains("5 or more transactions from the account within 2 minutes")));

        Transaction savedTxn = transactionRepository.findByTransactionId(fifthTxnId).orElseThrow();
        TransactionFeature feature = transactionFeatureRepository.findByTransactionRefId(savedTxn.getId()).orElseThrow();
        assertEquals(4, feature.getTransactionsLast2Min());
    }

    @Test
    @DisplayName("3b. Velocity: 3 previous transactions does NOT trigger VELOCITY")
    void testVelocityDoesNotTriggerAtThreePrevious() throws Exception {
        String accountId = "ACC-VEL3-" + UUID.randomUUID();
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-09-28T10:00:00+05:30");

        // Create 3 prior transactions
        for (int i = 0; i < 3; i++) {
            String priorTxnId = "TXN-VEL3-PRIOR-" + i + "-" + UUID.randomUUID();
            TransactionRequest req = createRequest(
                    priorTxnId, accountId, new BigDecimal("500.00"),
                    "DEV-VEL", "Delhi", baseTime.plusSeconds(i * 10)
            );
            mockMvc.perform(post("/api/transactions")
                            .header("Authorization", "Bearer " + validJwtToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        // 4th transaction
        String fourthTxnId = "TXN-VEL3-FOURTH-" + UUID.randomUUID();
        TransactionRequest fourthReq = createRequest(
                fourthTxnId, accountId, new BigDecimal("500.00"),
                "DEV-VEL", "Delhi", baseTime.plusSeconds(40)
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fourthReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleScore").value(0))
                .andExpect(jsonPath("$.triggeredRules", not(hasItem("VELOCITY"))));

        Transaction savedTxn = transactionRepository.findByTransactionId(fourthTxnId).orElseThrow();
        TransactionFeature feature = transactionFeatureRepository.findByTransactionRefId(savedTxn.getId()).orElseThrow();
        assertEquals(3, feature.getTransactionsLast2Min());
    }

    @Test
    @DisplayName("4. High Amount: ratio >= 3.0 triggers HIGH_AMOUNT (+25)")
    void testHighAmountTrigger() throws Exception {
        String accountId = "ACC-HIGH-" + UUID.randomUUID();
        OffsetDateTime t1Time = OffsetDateTime.parse("2026-09-28T10:00:00+05:30");

        // Prior transaction with amount 1000.00
        String t1Id = "TXN-HIGH-1-" + UUID.randomUUID();
        TransactionRequest req1 = createRequest(
                t1Id, accountId, new BigDecimal("1000.00"),
                "DEV-HIGH", "Delhi", t1Time
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Second transaction with amount 3000.00 (3x average)
        String t2Id = "TXN-HIGH-2-" + UUID.randomUUID();
        TransactionRequest req2 = createRequest(
                t2Id, accountId, new BigDecimal("3000.00"),
                "DEV-HIGH", "Delhi", t1Time.plusHours(2)
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleScore").value(25))
                .andExpect(jsonPath("$.triggeredRules", contains("HIGH_AMOUNT")))
                .andExpect(jsonPath("$.reasons", contains("Transaction amount is at least 3x the account historical average")));

        Transaction savedTxn = transactionRepository.findByTransactionId(t2Id).orElseThrow();
        TransactionFeature feature = transactionFeatureRepository.findByTransactionRefId(savedTxn.getId()).orElseThrow();
        assertEquals(new BigDecimal("1000.00"), feature.getAccountAvgAmount());
        assertEquals(new BigDecimal("3.0000"), feature.getAmountRatio());
    }

    @Test
    @DisplayName("5. Odd Hour: India timezone conversion and UTC instant")
    void testOddHourTimezones() throws Exception {
        String accountId = "ACC-ODD-" + UUID.randomUUID();

        // Test 2026-09-28T23:30:00+05:30 -> hour 23, oddHour = true
        String t1Id = "TXN-ODD-1-" + UUID.randomUUID();
        TransactionRequest req1 = createRequest(
                t1Id, accountId, new BigDecimal("1000.00"),
                "DEV-1", "Delhi", OffsetDateTime.parse("2026-09-28T23:30:00+05:30")
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        Transaction saved1 = transactionRepository.findByTransactionId(t1Id).orElseThrow();
        TransactionFeature feat1 = transactionFeatureRepository.findByTransactionRefId(saved1.getId()).orElseThrow();
        assertEquals((short) 23, feat1.getTransactionHour());
        assertTrue(feat1.getOddHour());

        // Test 2026-09-28T18:00:00Z -> 23:30 IST -> hour 23, oddHour = true
        String t2Id = "TXN-ODD-2-" + UUID.randomUUID();
        TransactionRequest req2 = createRequest(
                t2Id, accountId, new BigDecimal("1000.00"),
                "DEV-1", "Delhi", OffsetDateTime.parse("2026-09-28T18:00:00Z")
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated());

        Transaction saved2 = transactionRepository.findByTransactionId(t2Id).orElseThrow();
        TransactionFeature feat2 = transactionFeatureRepository.findByTransactionRefId(saved2.getId()).orElseThrow();
        assertEquals((short) 23, feat2.getTransactionHour());
        assertTrue(feat2.getOddHour());

        // Test 2026-09-28T10:00:00+05:30 -> hour 10, oddHour = false
        String t3Id = "TXN-ODD-3-" + UUID.randomUUID();
        TransactionRequest req3 = createRequest(
                t3Id, accountId, new BigDecimal("1000.00"),
                "DEV-1", "Delhi", OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req3)))
                .andExpect(status().isCreated());

        Transaction saved3 = transactionRepository.findByTransactionId(t3Id).orElseThrow();
        TransactionFeature feat3 = transactionFeatureRepository.findByTransactionRefId(saved3.getId()).orElseThrow();
        assertEquals((short) 10, feat3.getTransactionHour());
        assertFalse(feat3.getOddHour());
    }

    @Test
    @DisplayName("6. Multiple rules: New Device (15) + New Location (15) + Odd Hour High Value (15) = 45")
    void testMultipleRulesAdditive() throws Exception {
        String accountId = "ACC-MULTI-" + UUID.randomUUID();
        OffsetDateTime t1Time = OffsetDateTime.parse("2026-09-28T10:00:00+05:30");

        // Seed initial transaction to establish history (avg = 1000)
        String t1Id = "TXN-MULTI-1-" + UUID.randomUUID();
        TransactionRequest req1 = createRequest(
                t1Id, accountId, new BigDecimal("1000.00"),
                "DEV-ORIG", "Delhi", t1Time
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Next transaction: new device (DEV-NEW), new location (Goa), odd hour (23:30), amount 2500 (ratio 2.5 >= 2.0)
        // Expected: NEW_DEVICE (15) + NEW_LOCATION (15) + ODD_HOUR_HIGH_VALUE (15) = 45
        String t2Id = "TXN-MULTI-2-" + UUID.randomUUID();
        TransactionRequest req2 = createRequest(
                t2Id, accountId, new BigDecimal("2500.00"),
                "DEV-NEW", "Goa", OffsetDateTime.parse("2026-09-28T23:30:00+05:30")
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleScore").value(45))
                .andExpect(jsonPath("$.triggeredRules", containsInAnyOrder("NEW_DEVICE", "NEW_LOCATION", "ODD_HOUR_HIGH_VALUE")));
    }

    @Test
    @DisplayName("7. Current transaction exclusion from historical calculations")
    void testCurrentTransactionExclusion() throws Exception {
        String accountId = "ACC-EXCL-" + UUID.randomUUID();
        String txnId = "TXN-EXCL-1-" + UUID.randomUUID();

        TransactionRequest req = createRequest(
                txnId, accountId, new BigDecimal("5000.00"),
                "DEV-1", "Delhi", OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        Transaction savedTxn = transactionRepository.findByTransactionId(txnId).orElseThrow();
        TransactionFeature feature = transactionFeatureRepository.findByTransactionRefId(savedTxn.getId()).orElseThrow();

        // If current transaction was included, count would be 1 and avg would be 5000.00.
        // It must NOT be included:
        assertEquals(0, feature.getTransactionsLast2Min());
        assertEquals(0, feature.getTransactionsLast1Hour());
        assertEquals(new BigDecimal("0.00"), feature.getAccountAvgAmount());
        assertEquals(new BigDecimal("0.0000"), feature.getAmountRatio());
        assertNull(feature.getTimeSincePreviousTransactionSeconds());
    }

    @Test
    @DisplayName("10. Numeric Safety: Large amount and small average does not overflow NUMERIC(10,4)")
    void testNumericSafetyIntegration() throws Exception {
        String accountId = "ACC-NUM-" + UUID.randomUUID();

        // Seed with very small amount (1.00)
        String t1Id = "TXN-NUM-1-" + UUID.randomUUID();
        TransactionRequest req1 = createRequest(
                t1Id, accountId, new BigDecimal("1.00"),
                "DEV-1", "Delhi", OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Second transaction with very large amount (9999999.00)
        String t2Id = "TXN-NUM-2-" + UUID.randomUUID();
        TransactionRequest req2 = createRequest(
                t2Id, accountId, new BigDecimal("9999999.00"),
                "DEV-1", "Delhi", OffsetDateTime.parse("2026-09-28T10:05:00+05:30")
        );

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("APPROVE"));

        Transaction savedTxn = transactionRepository.findByTransactionId(t2Id).orElseThrow();
        TransactionFeature feature = transactionFeatureRepository.findByTransactionRefId(savedTxn.getId()).orElseThrow();

        // Must be capped at 999999.9999 and not fail Hibernate or DB NUMERIC(10,4) constraint
        assertEquals(new BigDecimal("999999.9999"), feature.getAmountRatio());
    }
}
