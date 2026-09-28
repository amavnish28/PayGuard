package com.payguard.transaction;

import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request) {
        if (transactionRepository.existsByTransactionId(request.getTransactionId())) {
            throw new DuplicateTransactionException("Transaction with ID " + request.getTransactionId() + " already exists");
        }

        Transaction transaction = new Transaction();
        transaction.setTransactionId(request.getTransactionId());
        transaction.setAccountId(request.getAccountId());
        transaction.setAmount(request.getAmount());
        transaction.setCurrency(request.getCurrency());
        transaction.setDeviceId(request.getDeviceId());
        transaction.setLocation(request.getLocation());
        transaction.setMerchantType(request.getMerchantType());
        transaction.setTransactionTimestamp(request.getTransactionTimestamp());
        transaction.setIsFraud(null);

        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTransactionException("Transaction with ID " + request.getTransactionId() + " already exists");
        }

        return new TransactionResponse(
                transaction.getTransactionId(),
                "APPROVE",
                "Transaction accepted for processing"
        );
    }
}
