package com.payguard.ml;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MLFeatureVectorMapperTest {

    @Test
    @DisplayName("First transaction mapping: is_first_transaction=1 and time_since_previous_seconds=-1.0")
    void testFirstTransactionMapping() {
        Transaction txn = new Transaction();
        txn.setAmount(new BigDecimal("25.50"));
        txn.setTransactionTimestamp(OffsetDateTime.parse("2026-09-30T10:00:00+05:30"));

        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null, // No previous transaction -> null
                true,
                true,
                (short) 10,
                false,
                false // hasPreviousTransactions = false
        );

        MLPredictionRequest request = MLFeatureVectorMapper.toPredictionRequest(txn, features);

        assertNotNull(request);
        assertEquals(25.50, request.getAmount(), 0.0001);
        assertEquals(0, request.getTransactionsLast2Min());
        assertEquals(0, request.getTransactionsLast1Hour());
        assertEquals(0.0, request.getAccountAvgAmount(), 0.0001);
        assertEquals(0.0, request.getAmountRatio(), 0.0001);
        assertEquals(-1.0, request.getTimeSincePreviousSeconds(), 0.0001);
        assertEquals(1, request.getIsFirstTransaction());
        assertEquals(1, request.getNewDevice());
        assertEquals(1, request.getNewLocation());
        assertEquals(10, request.getTransactionHour());
        assertEquals(0, request.getOddHour());
    }

    @Test
    @DisplayName("Subsequent transaction mapping: is_first_transaction=0 and real elapsed seconds")
    void testSubsequentTransactionMapping() {
        Transaction txn = new Transaction();
        txn.setAmount(new BigDecimal("150.00"));
        txn.setTransactionTimestamp(OffsetDateTime.parse("2026-09-30T23:30:00+05:30"));

        BehavioralFeatureResult features = new BehavioralFeatureResult(
                2,
                5,
                new BigDecimal("100.00"),
                new BigDecimal("1.5000"),
                300L, // 300 seconds elapsed
                false,
                false,
                (short) 23,
                true,
                true // hasPreviousTransactions = true
        );

        MLPredictionRequest request = MLFeatureVectorMapper.toPredictionRequest(txn, features);

        assertNotNull(request);
        assertEquals(150.00, request.getAmount(), 0.0001);
        assertEquals(2, request.getTransactionsLast2Min());
        assertEquals(5, request.getTransactionsLast1Hour());
        assertEquals(100.00, request.getAccountAvgAmount(), 0.0001);
        assertEquals(1.5000, request.getAmountRatio(), 0.0001);
        assertEquals(300.0, request.getTimeSincePreviousSeconds(), 0.0001);
        assertEquals(0, request.getIsFirstTransaction());
        assertEquals(0, request.getNewDevice());
        assertEquals(0, request.getNewLocation());
        assertEquals(23, request.getTransactionHour());
        assertEquals(1, request.getOddHour());
    }
}
