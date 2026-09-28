package com.payguard.transaction;

import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

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
    @DisplayName("createTransaction successfully maps, sets isFraud=null, saves and returns APPROVE")
    void testCreateTransactionSuccess() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = transactionService.createTransaction(sampleRequest);

        assertNotNull(response);
        assertEquals(sampleRequest.getTransactionId(), response.getTransactionId());
        assertEquals("APPROVE", response.getDecision());
        assertEquals("Transaction accepted for processing", response.getMessage());

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
    }

    @Test
    @DisplayName("createTransaction throws DuplicateTransactionException when transactionId already exists in pre-check")
    void testCreateTransactionDuplicatePreCheck() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(true);

        assertThrows(DuplicateTransactionException.class, () -> transactionService.createTransaction(sampleRequest));
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("createTransaction catches DataIntegrityViolationException on race condition and throws DuplicateTransactionException")
    void testCreateTransactionRaceCondition() {
        when(transactionRepository.existsByTransactionId(sampleRequest.getTransactionId())).thenReturn(false);
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation on transaction_id"));

        assertThrows(DuplicateTransactionException.class, () -> transactionService.createTransaction(sampleRequest));
    }
}
