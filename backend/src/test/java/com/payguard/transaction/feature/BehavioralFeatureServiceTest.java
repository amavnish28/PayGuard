package com.payguard.transaction.feature;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BehavioralFeatureServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private BehavioralFeatureService featureService;

    @BeforeEach
    void setUp() {
        featureService = new BehavioralFeatureService(transactionRepository, "Asia/Kolkata");
    }

    private Transaction createSampleTransaction(String accountId, BigDecimal amount, OffsetDateTime timestamp) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setTransactionId("TXN-" + UUID.randomUUID());
        t.setAccountId(accountId);
        t.setAmount(amount);
        t.setCurrency("INR");
        t.setDeviceId("DEV-001");
        t.setLocation("Delhi");
        t.setMerchantType("RETAIL");
        t.setTransactionTimestamp(timestamp);
        return t;
    }

    @Test
    @DisplayName("Odd hour calculation: 2026-09-28T23:30:00+05:30 -> hour 23, oddHour = true")
    void testOddHourKolkataTimezone() {
        Transaction t = createSampleTransaction("ACC-01", new BigDecimal("1000.00"),
                OffsetDateTime.parse("2026-09-28T23:30:00+05:30"));

        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        BehavioralFeatureResult result = featureService.calculateFeatures(t);

        assertEquals((short) 23, result.getTransactionHour());
        assertTrue(result.getOddHour());
    }

    @Test
    @DisplayName("Odd hour calculation: 2026-09-28T18:00:00Z -> 23:30 IST -> hour 23, oddHour = true")
    void testOddHourUtcInstantConvertedToIst() {
        Transaction t = createSampleTransaction("ACC-01", new BigDecimal("1000.00"),
                OffsetDateTime.parse("2026-09-28T18:00:00Z"));

        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        BehavioralFeatureResult result = featureService.calculateFeatures(t);

        assertEquals((short) 23, result.getTransactionHour());
        assertTrue(result.getOddHour());
    }

    @Test
    @DisplayName("Odd hour calculation: 2026-09-28T10:00:00+05:30 -> hour 10, oddHour = false")
    void testNonOddHourKolkata() {
        Transaction t = createSampleTransaction("ACC-01", new BigDecimal("1000.00"),
                OffsetDateTime.parse("2026-09-28T10:00:00+05:30"));

        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        BehavioralFeatureResult result = featureService.calculateFeatures(t);

        assertEquals((short) 10, result.getTransactionHour());
        assertFalse(result.getOddHour());
    }

    @Test
    @DisplayName("Odd hour boundaries: hour 0 and 5 are odd, hour 6 and 22 are not odd")
    void testOddHourBoundaryValues() {
        Transaction t0 = createSampleTransaction("ACC-01", new BigDecimal("1000.00"),
                OffsetDateTime.parse("2026-09-28T00:15:00+05:30"));
        Transaction t5 = createSampleTransaction("ACC-01", new BigDecimal("1000.00"),
                OffsetDateTime.parse("2026-09-28T05:59:00+05:30"));
        Transaction t6 = createSampleTransaction("ACC-01", new BigDecimal("1000.00"),
                OffsetDateTime.parse("2026-09-28T06:00:00+05:30"));
        Transaction t22 = createSampleTransaction("ACC-01", new BigDecimal("1000.00"),
                OffsetDateTime.parse("2026-09-28T22:59:00+05:30"));

        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        assertTrue(featureService.calculateFeatures(t0).getOddHour(), "Hour 0 should be odd");
        assertTrue(featureService.calculateFeatures(t5).getOddHour(), "Hour 5 should be odd");
        assertFalse(featureService.calculateFeatures(t6).getOddHour(), "Hour 6 should not be odd");
        assertFalse(featureService.calculateFeatures(t22).getOddHour(), "Hour 22 should not be odd");
    }

    @Test
    @DisplayName("Numeric safety: ratio capped at 999999.9999 and scaled to 4 decimals")
    void testNumericSafetyAndCapping() {
        Transaction t = createSampleTransaction("ACC-01", new BigDecimal("5000000.00"),
                OffsetDateTime.parse("2026-09-28T10:00:00+05:30"));

        when(transactionRepository.findHistoricalAverageAmount(any(), any(), any()))
                .thenReturn(new BigDecimal("0.01"));

        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        BehavioralFeatureResult result = featureService.calculateFeatures(t);

        assertEquals(new BigDecimal("0.01"), result.getAccountAvgAmount());
        assertEquals(new BigDecimal("999999.9999"), result.getAmountRatio());
    }

    @Test
    @DisplayName("Zero historical average results in 0.00 avg and 0.0000 ratio")
    void testZeroHistoricalAverage() {
        Transaction t = createSampleTransaction("ACC-01", new BigDecimal("1500.00"),
                OffsetDateTime.parse("2026-09-28T10:00:00+05:30"));

        when(transactionRepository.findHistoricalAverageAmount(any(), any(), any()))
                .thenReturn(null);

        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        BehavioralFeatureResult result = featureService.calculateFeatures(t);

        assertEquals(new BigDecimal("0.00"), result.getAccountAvgAmount());
        assertEquals(new BigDecimal("0.0000"), result.getAmountRatio());
        assertFalse(result.hasPreviousTransactions());
    }

    @Test
    @DisplayName("First transaction cold start features")
    void testColdStartFeatures() {
        Transaction t = createSampleTransaction("ACC-COLD", new BigDecimal("1500.00"),
                OffsetDateTime.parse("2026-09-28T10:00:00+05:30"));

        when(transactionRepository.countPreviousTransactionsInRange(any(), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.findHistoricalAverageAmount(any(), any(), any()))
                .thenReturn(null);
        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.isDevicePreviouslyUsed(any(), any(), any(), any()))
                .thenReturn(false);
        when(transactionRepository.isLocationPreviouslyUsed(any(), any(), any(), any()))
                .thenReturn(false);

        BehavioralFeatureResult result = featureService.calculateFeatures(t);

        assertEquals(0, result.getTransactionsLast2Min());
        assertEquals(0, result.getTransactionsLast1Hour());
        assertEquals(new BigDecimal("0.00"), result.getAccountAvgAmount());
        assertEquals(new BigDecimal("0.0000"), result.getAmountRatio());
        assertNull(result.getTimeSincePreviousTransactionSeconds());
        assertTrue(result.getNewDevice());
        assertTrue(result.getNewLocation());
        assertFalse(result.hasPreviousTransactions());
    }

    @Test
    @DisplayName("Subsequent transaction with previous transaction computes time difference in seconds")
    void testSubsequentTransactionTimeDifference() {
        OffsetDateTime t1Time = OffsetDateTime.parse("2026-09-28T10:00:00+05:30");
        OffsetDateTime t2Time = OffsetDateTime.parse("2026-09-28T10:01:15+05:30");

        Transaction previousTxn = createSampleTransaction("ACC-01", new BigDecimal("1000.00"), t1Time);
        Transaction currentTxn = createSampleTransaction("ACC-01", new BigDecimal("1200.00"), t2Time);

        when(transactionRepository.findPreviousTransactions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(previousTxn));
        when(transactionRepository.isDevicePreviouslyUsed(any(), any(), any(), any()))
                .thenReturn(true);
        when(transactionRepository.isLocationPreviouslyUsed(any(), any(), any(), any()))
                .thenReturn(true);
        when(transactionRepository.findHistoricalAverageAmount(any(), any(), any()))
                .thenReturn(new BigDecimal("1000.00"));

        BehavioralFeatureResult result = featureService.calculateFeatures(currentTxn);

        assertEquals(75L, result.getTimeSincePreviousTransactionSeconds());
        assertFalse(result.getNewDevice());
        assertFalse(result.getNewLocation());
        assertTrue(result.hasPreviousTransactions());
        assertEquals(new BigDecimal("1000.00"), result.getAccountAvgAmount());
        assertEquals(new BigDecimal("1.2000"), result.getAmountRatio());
    }
}
