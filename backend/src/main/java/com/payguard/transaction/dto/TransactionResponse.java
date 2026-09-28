package com.payguard.transaction.dto;

import java.util.ArrayList;
import java.util.List;

public class TransactionResponse {

    private String transactionId;
    private String decision;
    private String message;

    // TODO: ruleScore, triggeredRules, and reasons are temporary verification fields.
    // They must be removed or restricted before the dashboard phase because exposing
    // exact fraud thresholds and detection reasons to external clients can reveal detection logic.
    private Integer ruleScore;
    private List<String> triggeredRules;
    private List<String> reasons;

    public TransactionResponse() {
    }

    public TransactionResponse(String transactionId, String decision, String message) {
        this.transactionId = transactionId;
        this.decision = decision;
        this.message = message;
    }

    public TransactionResponse(String transactionId, String decision, String message,
                               Integer ruleScore, List<String> triggeredRules, List<String> reasons) {
        this.transactionId = transactionId;
        this.decision = decision;
        this.message = message;
        this.ruleScore = ruleScore;
        this.triggeredRules = triggeredRules != null ? triggeredRules : new ArrayList<>();
        this.reasons = reasons != null ? reasons : new ArrayList<>();
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getRuleScore() {
        return ruleScore;
    }

    public void setRuleScore(Integer ruleScore) {
        this.ruleScore = ruleScore;
    }

    public List<String> getTriggeredRules() {
        return triggeredRules;
    }

    public void setTriggeredRules(List<String> triggeredRules) {
        this.triggeredRules = triggeredRules;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
    }
}
