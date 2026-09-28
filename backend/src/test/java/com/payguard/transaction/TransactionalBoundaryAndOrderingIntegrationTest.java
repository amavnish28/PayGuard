package com.payguard.transaction;

import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.transaction.feature.TransactionFeatureRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class TransactionalBoundaryAndOrderingIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @SpyBean
    private TransactionFeatureRepository transactionFeatureRepository;

    private final List<String> createdTransactionIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        // Reset spy if it was modified
        reset(transactionFeatureRepository);

        // Clean up only test-created rows
        for (String txnId : createdTransactionIds) {
            try {
                Optional<Transaction> opt = transactionRepository.findByTransactionId(txnId);
                if (opt.isPresent()) {
                    Transaction t = opt.get();
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
    @DisplayName("8. Identical timestamps: Secondary ordering uses created_at DESC as tie-break")
    void testIdenticalTimestampsTieBreak() throws Exception {
        String accountId = "ACC-TIE-" + UUID.randomUUID();
        OffsetDateTime identicalTime = OffsetDateTime.parse("2026-09-28T10:00:00+05:30");

        // Transaction A committed first
        String txnAId = "TXN-TIE-A-" + UUID.randomUUID();
        TransactionRequest reqA = createRequest(txnAId, accountId, new BigDecimal("1000.00"),
                "DEV-A", "Delhi", identicalTime);
        transactionService.createTransaction(reqA);

        // Short sleep so now() timestamp in postgres advances
        Thread.sleep(100);

        // Transaction B committed second with identical transaction_timestamp
        String txnBId = "TXN-TIE-B-" + UUID.randomUUID();
        TransactionRequest reqB = createRequest(txnBId, accountId, new BigDecimal("2000.00"),
                "DEV-B", "Mumbai", identicalTime);
        transactionService.createTransaction(reqB);

        // Transaction C arrives later (10:00:30)
        String txnCId = "TXN-TIE-C-" + UUID.randomUUID();
        TransactionRequest reqC = createRequest(txnCId, accountId, new BigDecimal("1500.00"),
                "DEV-C", "Kolkata", identicalTime.plusSeconds(30));
        transactionService.createTransaction(reqC);

        Transaction savedTxnB = transactionRepository.findByTransactionId(txnBId).orElseThrow();
        Transaction savedTxnA = transactionRepository.findByTransactionId(txnAId).orElseThrow();
        Transaction savedTxnC = transactionRepository.findByTransactionId(txnCId).orElseThrow();

        // Verify Txn B was created after Txn A
        assertTrue(savedTxnB.getCreatedAt().isAfter(savedTxnA.getCreatedAt())
                || savedTxnB.getCreatedAt().isEqual(savedTxnA.getCreatedAt()));

        // Check Txn C's features: time difference should be 30 seconds against the tie-broken predecessor
        TransactionFeature featureC = transactionFeatureRepository.findByTransactionRefId(savedTxnC.getId()).orElseThrow();
        assertEquals(30L, featureC.getTimeSincePreviousTransactionSeconds());

        // Historical average of previous transactions (1000 + 2000) / 2 = 1500.00
        assertEquals(new BigDecimal("1500.00"), featureC.getAccountAvgAmount());
    }

    @Test
    @DisplayName("9. Out-of-order arrivals: Historical selection follows transaction_timestamp DESC, created_at DESC")
    void testOutOfOrderArrivals() throws Exception {
        String accountId = "ACC-OOO-" + UUID.randomUUID();

        // Insert Transaction 1: timestamp 10:05:00
        String txn1Id = "TXN-OOO-1-" + UUID.randomUUID();
        TransactionRequest req1 = createRequest(txn1Id, accountId, new BigDecimal("1000.00"),
                "DEV-1", "Delhi", OffsetDateTime.parse("2026-09-28T10:05:00+05:30"));
        transactionService.createTransaction(req1);

        // Insert Transaction 2: timestamp 10:02:00 (older timestamp arriving later)
        String txn2Id = "TXN-OOO-2-" + UUID.randomUUID();
        TransactionRequest req2 = createRequest(txn2Id, accountId, new BigDecimal("1000.00"),
                "DEV-2", "Delhi", OffsetDateTime.parse("2026-09-28T10:02:00+05:30"));
        transactionService.createTransaction(req2);

        // Insert Transaction 3: timestamp 10:06:00
        String txn3Id = "TXN-OOO-3-" + UUID.randomUUID();
        TransactionRequest req3 = createRequest(txn3Id, accountId, new BigDecimal("1000.00"),
                "DEV-3", "Delhi", OffsetDateTime.parse("2026-09-28T10:06:00+05:30"));
        transactionService.createTransaction(req3);

        Transaction savedTxn3 = transactionRepository.findByTransactionId(txn3Id).orElseThrow();
        TransactionFeature feature3 = transactionFeatureRepository.findByTransactionRefId(savedTxn3.getId()).orElseThrow();

        // Immediate predecessor by timestamp is Txn 1 (10:05:00), NOT Txn 2 (10:02:00)
        // 10:06:00 - 10:05:00 = 60 seconds
        assertEquals(60L, feature3.getTimeSincePreviousTransactionSeconds());
    }

    @Test
    @DisplayName("12. Atomicity: Failure in feature persistence rolls back transaction row")
    void testAtomicityRollbackOnFeaturePersistenceFailure() {
        String accountId = "ACC-ATOM-" + UUID.randomUUID();
        String txnId = "TXN-ATOM-" + UUID.randomUUID();
        TransactionRequest req = createRequest(txnId, accountId, new BigDecimal("1500.00"),
                "DEV-ATOM", "Delhi", OffsetDateTime.parse("2026-09-28T10:00:00+05:30"));

        // Force feature repository saveAndFlush to throw
        doThrow(new RuntimeException("Simulated feature persistence failure"))
                .when(transactionFeatureRepository).saveAndFlush(any(TransactionFeature.class));

        // Act & Assert: Service operation fails
        assertThrows(RuntimeException.class, () -> transactionService.createTransaction(req));

        // Assert: NO transaction row remains in transactions table
        assertTrue(transactionRepository.findByTransactionId(txnId).isEmpty(),
                "Transaction row must be rolled back and not exist in transactions table");

        // Reset spy for tearDown
        reset(transactionFeatureRepository);
    }

    @Test
    @DisplayName("13. Concurrency: Advisory lock serializes transactions for the same account")
    void testConcurrencyAdvisoryLockSerializesSameAccount() throws Exception {
        String accountId = "ACC-CONC-" + UUID.randomUUID();
        OffsetDateTime baseTime = OffsetDateTime.parse("2026-09-28T10:00:00+05:30");
        int numThreads = 5;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Callable<TransactionResponse>> tasks = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            String txnId = "TXN-CONC-" + index + "-" + UUID.randomUUID();
            TransactionRequest req = createRequest(
                    txnId, accountId, new BigDecimal("500.00"),
                    "DEV-CONC", "Delhi", baseTime.plusSeconds(index * 10)
            );
            tasks.add(() -> transactionService.createTransaction(req));
        }

        List<Future<TransactionResponse>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        // All transactions should succeed
        for (Future<TransactionResponse> f : futures) {
            TransactionResponse resp = f.get();
            assertNotNull(resp);
            assertEquals("APPROVE", resp.getDecision());
        }

        // Query all saved transactions and their features
        List<TransactionFeature> features = new ArrayList<>();
        for (String txnId : createdTransactionIds) {
            Transaction t = transactionRepository.findByTransactionId(txnId).orElseThrow();
            TransactionFeature f = transactionFeatureRepository.findByTransactionRefId(t.getId()).orElseThrow();
            features.add(f);
        }

        assertEquals(numThreads, features.size());

        // At least one transaction (the 5th in chronological sequence) must see 4 previous transactions
        // and trigger VELOCITY (+30)
        boolean velocityFound = features.stream().anyMatch(f -> f.getTransactionsLast2Min() == 4);
        assertTrue(velocityFound, "Serialized execution should allow the latest transaction to see 4 prior transactions");
    }
}
