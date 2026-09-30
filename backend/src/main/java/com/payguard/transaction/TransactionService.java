package com.payguard.transaction;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import com.payguard.decision.Decision;
import com.payguard.decision.HybridDecisionEngine;
import com.payguard.decision.HybridDecisionResult;
import com.payguard.ml.MLFeatureVectorMapper;
import com.payguard.ml.MLPredictionRequest;
import com.payguard.ml.MLPredictionResponse;
import com.payguard.ml.MLServiceClient;
import com.payguard.transaction.dto.TransactionRequest;
import com.payguard.transaction.dto.TransactionResponse;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import com.payguard.transaction.feature.BehavioralFeatureService;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.transaction.feature.TransactionFeatureRepository;
import com.payguard.transaction.rule.FraudRulesEngine;
import com.payguard.transaction.rule.RuleEvaluation;
import com.payguard.transaction.rule.RuleTier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final TransactionFeatureRepository transactionFeatureRepository;
    private final BehavioralFeatureService behavioralFeatureService;
    private final FraudRulesEngine fraudRulesEngine;
    private final MLServiceClient mlServiceClient;
    private final HybridDecisionEngine hybridDecisionEngine;
    private final AlertRepository alertRepository;
    private final AuditLogRepository auditLogRepository;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionFeatureRepository transactionFeatureRepository,
                              BehavioralFeatureService behavioralFeatureService,
                              FraudRulesEngine fraudRulesEngine,
                              MLServiceClient mlServiceClient,
                              HybridDecisionEngine hybridDecisionEngine,
                              AlertRepository alertRepository,
                              AuditLogRepository auditLogRepository) {
        this.transactionRepository = transactionRepository;
        this.transactionFeatureRepository = transactionFeatureRepository;
        this.behavioralFeatureService = behavioralFeatureService;
        this.fraudRulesEngine = fraudRulesEngine;
        this.mlServiceClient = mlServiceClient;
        this.hybridDecisionEngine = hybridDecisionEngine;
        this.alertRepository = alertRepository;
        this.auditLogRepository = auditLogRepository;
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

        // 4. Build ML prediction request
        MLPredictionRequest mlRequest = MLFeatureVectorMapper.toPredictionRequest(transaction, features);

        // 5. Call ML service client
        Optional<MLPredictionResponse> mlResponse = mlServiceClient.predict(mlRequest);

        // 6. Compute hybrid decision
        HybridDecisionResult decisionResult = hybridDecisionEngine.evaluate(ruleEvaluation, mlResponse);

        // 7. IF decision is REVIEW or BLOCK: create and save Alert entity in alerts table
        if (decisionResult.getDecision() == Decision.REVIEW || decisionResult.getDecision() == Decision.BLOCK) {
            Alert alert = new Alert();
            alert.setTransactionId(transaction.getId());
            if (decisionResult.getFraudProbability() != null) {
                alert.setFraudProbability(BigDecimal.valueOf(decisionResult.getFraudProbability()).setScale(4, RoundingMode.HALF_UP));
            } else {
                alert.setFraudProbability(null);
            }

            // rule_score column in PostgreSQL alerts table is NUMERIC(5,2) (raw integer range 0.00-100.00)
            alert.setRuleScore(BigDecimal.valueOf(decisionResult.getRuleScore()).setScale(2, RoundingMode.HALF_UP));

            if (decisionResult.getFinalScore() != null) {
                alert.setFinalScore(BigDecimal.valueOf(decisionResult.getFinalScore()).setScale(4, RoundingMode.HALF_UP));
            } else {
                alert.setFinalScore(null);
            }

            alert.setDecision(decisionResult.getDecision());
            alert.setSeverity(deriveSeverity(decisionResult));
            alert.setStatus(AlertStatus.OPEN);

            Map<String, Object> explanation = new LinkedHashMap<>();
            explanation.put("ruleScore", decisionResult.getRuleScore());
            explanation.put("ruleTier", decisionResult.getRuleTier().name());
            explanation.put("triggeredRules", decisionResult.getTriggeredRules());
            explanation.put("ruleReasons", decisionResult.getRuleReasons());
            explanation.put("mlAvailable", decisionResult.isMlAvailable());
            explanation.put("fraudProbability", decisionResult.getFraudProbability());
            explanation.put("mlBand", decisionResult.getMlBand());
            explanation.put("shapReasons", decisionResult.getShapReasons());
            alert.setExplanation(explanation);

            alertRepository.saveAndFlush(alert);
        }

        // 8. IF ML was unavailable: write one row to audit_logs
        if (!decisionResult.isMlAvailable()) {
            AuditLog auditLog = new AuditLog();
            auditLog.setAction("ML_SERVICE_UNAVAILABLE");
            auditLog.setEntityType("transaction");
            auditLog.setEntityId(transaction.getTransactionId());

            Map<String, Object> details = new LinkedHashMap<>();
            String reason = mlServiceClient.getLastFailureReason();
            details.put("reason", reason != null ? reason : "unknown");
            details.put("ruleTier", decisionResult.getRuleTier().name());
            details.put("fallbackDecision", decisionResult.getDecision().name());
            auditLog.setDetails(details);
            auditLog.setUserId(null);
            auditLog.setIpAddress(null);

            auditLogRepository.saveAndFlush(auditLog);
        }

        // 9. Build response with real hybrid decision
        // TODO: Do NOT expose shapReasons or the internal alert id in this response yet
        // (that belongs to a dedicated alert-detail endpoint in a later phase).
        return new TransactionResponse(
                transaction.getTransactionId(),
                decisionResult.getDecision().name(),
                getMessageForDecision(decisionResult.getDecision()),
                decisionResult.getRuleScore(),
                decisionResult.getTriggeredRules(),
                decisionResult.getRuleReasons(),
                decisionResult.isMlAvailable(),
                decisionResult.getFraudProbability(),
                decisionResult.getMlBand()
        );
    }

    /**
     * Severity mapping:
     * BLOCK + mlAvailable=true + ruleTier=HIGH -> CRITICAL
     * BLOCK otherwise -> HIGH
     * REVIEW + ruleTier=HIGH -> MEDIUM
     * REVIEW otherwise -> LOW
     * APPROVE -> no alert row
     */
    private SeverityLevel deriveSeverity(HybridDecisionResult result) {
        if (result.getDecision() == Decision.BLOCK) {
            if (result.isMlAvailable() && result.getRuleTier() == RuleTier.HIGH) {
                return SeverityLevel.CRITICAL;
            }
            return SeverityLevel.HIGH;
        } else if (result.getDecision() == Decision.REVIEW) {
            if (result.getRuleTier() == RuleTier.HIGH) {
                return SeverityLevel.MEDIUM;
            }
            return SeverityLevel.LOW;
        }
        return SeverityLevel.LOW;
    }

    private String getMessageForDecision(Decision decision) {
        return switch (decision) {
            case APPROVE -> "Transaction accepted for processing";
            case REVIEW -> "Transaction flagged for analyst review";
            case BLOCK -> "Transaction blocked due to high fraud risk";
        };
    }
}
