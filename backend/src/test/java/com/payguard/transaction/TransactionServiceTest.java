package com.payguard.transaction;

import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import com.payguard.transaction.feature.BehavioralFeatureService;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.transaction.feature.TransactionFeatureRepository;
import com.payguard.transaction.rule.FraudRulesEngine;
import com.payguard.transaction.rule.RuleEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionFeatureRepository transactionFeatureRepository;

    @Mock
    private BehavioralFeatureService behavioralFeatureService;

    @Mock
    private FraudRulesEngine fraudRulesEngine;

    @InjectMocks
    private TransactionService transactionService;

    private TransactionRequest sampleRequest;

    @BeforeEach
    void setUp() {
        sampleRequest = new TransactionRequest(
                "TXN-" + UUID.randomUUID(),
                "ACC-001",
                new BigDecimal("1500.00"),
                "INR",
                "DEV-001",
                "Delhi",
                "RETAIL",
                OffsetDateTime.parse("2026-09-28T10:00:00+05:30")
        );
    }

    @Test
    @DisplayName("createTransaction successfully maps, sets isFraud=null, saves transaction and features, evaluates rules and returns APPROVE")
    void testCreateTransactionSuccess() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        BehavioralFeatureResult featureResult = new BehavioralFeatureResult(
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, true, true, (short) 10, false, false
        );
        when(behavioralFeatureService.calculateFeatures(any(Transaction.class))).thenReturn(featureResult);

        RuleEvaluation ruleEvaluation = new RuleEvaluation(0, Collections.emptyList(), Collections.emptyList());
        when(fraudRulesEngine.evaluate(any(Transaction.class), any(BehavioralFeatureResult.class))).thenReturn(ruleEvaluation);

        TransactionResponse response = transactionService.createTransaction(sampleRequest);

        assertNotNull(response);
        assertEquals(sampleRequest.getTransactionId(), response.getTransactionId());
        assertEquals("APPROVE", response.getDecision());
        assertEquals("Transaction accepted for processing", response.getMessage());
        assertEquals(0, response.getRuleScore());
        assertTrue(response.getTriggeredRules().isEmpty());
        assertTrue(response.getReasons().isEmpty());

        verify(transactionRepository).acquireAccountAdvisoryLock(sampleRequest.getAccountId());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        Transaction saved = captor.getValue();
        assertEquals(sampleRequest.getTransactionId(), saved.getTransactionId());
        assertEquals(sampleRequest.getAccountId(), saved.getAccountId());
        assertEquals(sampleRequest.getAmount(), saved.getAmount());
        assertEquals(sampleRequest.getCurrency(), saved.getCurrency());
        assertEquals(sampleRequest.getDeviceId(), saved.getDeviceId());
        assertEquals(sampleRequest.getLocation(), saved.getLocation());
        assertEquals(sampleRequest.getMerchantType(), saved.getMerchantType());
        assertEquals(sampleRequest.getTransactionTimestamp(), saved.getTransactionTimestamp());
        assertNull(saved.getIsFraud(), "isFraud must remain null");

        ArgumentCaptor<TransactionFeature> featureCaptor = ArgumentCaptor.forClass(TransactionFeature.class);
        verify(transactionFeatureRepository).saveAndFlush(featureCaptor.capture());
        TransactionFeature savedFeature = featureCaptor.getValue();
        assertEquals(saved.getId(), savedFeature.getTransactionRefId());
        assertEquals(0, savedFeature.getTransactionsLast2Min());
        assertTrue(savedFeature.getNewDevice());
        assertTrue(savedFeature.getNewLocation());
    }

    @Test
    @DisplayName("createTransaction throws DuplicateTransactionException when transactionId already exists in pre-check")
    void testCreateTransactionDuplicatePreCheck() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(true);

        assertThrows(DuplicateTransactionException.class, () -> transactionService.createTransaction(sampleRequest));
        verify(transactionRepository).acquireAccountAdvisoryLock(sampleRequest.getAccountId());
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(transactionFeatureRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("createTransaction catches DataIntegrityViolationException on race condition and throws DuplicateTransactionException")
    void testCreateTransactionRaceCondition() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation on transaction_id"));

        assertThrows(DuplicateTransactionException.class, () -> transactionService.createTransaction(sampleRequest));
        verify(transactionRepository).acquireAccountAdvisoryLock(sampleRequest.getAccountId());
        verify(transactionFeatureRepository, never()).saveAndFlush(any());
    }
}
