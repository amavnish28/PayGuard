package com.payguard.transaction.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TransactionResult {

    private final String transactionId;
    private final String decision;
    private final String message;
    private final Integer ruleScore;
    private final List<String> triggeredRules;
    private final List<String> reasons;
    private final Boolean mlAvailable;
    private final Double fraudProbability;
    private final String mlBand;

    public TransactionResult(String transactionId, String decision, String message,
                             Integer ruleScore, List<String> triggeredRules, List<String> reasons,
                             Boolean mlAvailable, Double fraudProbability, String mlBand) {
        this.transactionId = transactionId;
        this.decision = decision;
        this.message = message;
        this.ruleScore = ruleScore;
        this.triggeredRules = triggeredRules != null ? Collections.unmodifiableList(new ArrayList<>(triggeredRules)) : Collections.emptyList();
        this.reasons = reasons != null ? Collections.unmodifiableList(new ArrayList<>(reasons)) : Collections.emptyList();
        this.mlAvailable = mlAvailable;
        this.fraudProbability = fraudProbability;
        this.mlBand = mlBand;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getDecision() {
        return decision;
    }

    public String getMessage() {
        return message;
    }

    public Integer getRuleScore() {
        return ruleScore;
    }

    public List<String> getTriggeredRules() {
        return triggeredRules;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public Boolean getMlAvailable() {
        return mlAvailable;
    }

    public Boolean isMlAvailable() {
        return mlAvailable;
    }

    public Double getFraudProbability() {
        return fraudProbability;
    }

    public String getMlBand() {
        return mlBand;
    }
}
