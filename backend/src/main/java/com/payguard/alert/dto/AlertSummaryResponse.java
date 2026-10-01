package com.payguard.alert.dto;

import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.decision.Decision;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class AlertSummaryResponse {

    private UUID id;
    private String transactionId;
    private Decision decision;
    private SeverityLevel severity;
    private AlertStatus status;
    private BigDecimal fraudProbability;
    private BigDecimal ruleScore;
    private OffsetDateTime createdAt;

    public AlertSummaryResponse() {
    }

    public AlertSummaryResponse(UUID id, String transactionId, Decision decision, SeverityLevel severity,
                                AlertStatus status, BigDecimal fraudProbability, BigDecimal ruleScore,
                                OffsetDateTime createdAt) {
        this.id = id;
        this.transactionId = transactionId;
        this.decision = decision;
        this.severity = severity;
        this.status = status;
        this.fraudProbability = fraudProbability;
        this.ruleScore = ruleScore;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public SeverityLevel getSeverity() {
        return severity;
    }

    public void setSeverity(SeverityLevel severity) {
        this.severity = severity;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public void setStatus(AlertStatus status) {
        this.status = status;
    }

    public BigDecimal getFraudProbability() {
        return fraudProbability;
    }

    public void setFraudProbability(BigDecimal fraudProbability) {
        this.fraudProbability = fraudProbability;
    }

    public BigDecimal getRuleScore() {
        return ruleScore;
    }

    public void setRuleScore(BigDecimal ruleScore) {
        this.ruleScore = ruleScore;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
