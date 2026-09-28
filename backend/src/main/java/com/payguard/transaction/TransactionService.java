package com.payguard.transaction;

import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import com.payguard.transaction.feature.BehavioralFeatureService;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.transaction.feature.TransactionFeatureRepository;
import com.payguard.transaction.rule.FraudRulesEngine;
import com.payguard.transaction.rule.RuleEvaluation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final TransactionFeatureRepository transactionFeatureRepository;
    private final BehavioralFeatureService behavioralFeatureService;
    private final FraudRulesEngine fraudRulesEngine;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionFeatureRepository transactionFeatureRepository,
                              BehavioralFeatureService behavioralFeatureService,
                              FraudRulesEngine fraudRulesEngine) {
        this.transactionRepository = transactionRepository;
        this.transactionFeatureRepository = transactionFeatureRepository;
        this.behavioralFeatureService = behavioralFeatureService;
        this.fraudRulesEngine = fraudRulesEngine;
    }

    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request) {
        // Concurrency: Acquire transaction-level advisory lock on account to serialize concurrent processing
        transactionRepository.acquireAccountAdvisoryLock(request.getAccountId());

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

        // 1. Calculate behavioral features
        BehavioralFeatureResult features = behavioralFeatureService.calculateFeatures(transaction);

        // 2. Persist exactly one row in transaction_features inside the same transaction boundary
        TransactionFeature featureEntity = new TransactionFeature();
        featureEntity.setTransactionRefId(transaction.getId());
        featureEntity.setTransactionsLast2Min(features.getTransactionsLast2Min());
        featureEntity.setTransactionsLast1Hour(features.getTransactionsLast1Hour());
        featureEntity.setAccountAvgAmount(features.getAccountAvgAmount());
        featureEntity.setAmountRatio(features.getAmountRatio());
        featureEntity.setTimeSincePreviousTransactionSeconds(features.getTimeSincePreviousTransactionSeconds());
        featureEntity.setNewDevice(features.getNewDevice());
        featureEntity.setNewLocation(features.getNewLocation());
        featureEntity.setTransactionHour(features.getTransactionHour());
        featureEntity.setOddHour(features.getOddHour());

        transactionFeatureRepository.saveAndFlush(featureEntity);

        // 3. Evaluate deterministic rules
        RuleEvaluation ruleEvaluation = fraudRulesEngine.evaluate(transaction, features);

        // TODO: ruleScore, triggeredRules, and reasons are temporary verification fields.
        // They must be removed or restricted before the dashboard phase because exposing
        // exact fraud thresholds and detection reasons to external clients can reveal detection logic.
        return new TransactionResponse(
                transaction.getTransactionId(),
                "APPROVE",
                "Transaction accepted for processing",
                ruleEvaluation.getRuleScore(),
                ruleEvaluation.getTriggeredRules(),
                ruleEvaluation.getReasons()
        );
    }
}
