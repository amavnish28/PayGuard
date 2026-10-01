package com.payguard.alert.dto;

import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.decision.Decision;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public class AlertDetailResponse extends AlertSummaryResponse {

    private BigDecimal finalScore;
    private Map<String, Object> explanation;
    private OffsetDateTime updatedAt;
    private BigDecimal amount;
    private String currency;
    private String deviceId;
    private String location;
    private String merchantType;
    private OffsetDateTime transactionTimestamp;
    private AlertVerdictSummary verdict;

    public AlertDetailResponse() {
    }

    public AlertDetailResponse(UUID id, String transactionId, Decision decision, SeverityLevel severity,
                               AlertStatus status, BigDecimal fraudProbability, BigDecimal ruleScore,
                               OffsetDateTime createdAt, BigDecimal finalScore, Map<String, Object> explanation,
                               OffsetDateTime updatedAt, BigDecimal amount, String currency, String deviceId,
                               String location, String merchantType, OffsetDateTime transactionTimestamp) {
        this(id, transactionId, decision, severity, status, fraudProbability, ruleScore, createdAt,
                finalScore, explanation, updatedAt, amount, currency, deviceId, location, merchantType,
                transactionTimestamp, null);
    }

    public AlertDetailResponse(UUID id, String transactionId, Decision decision, SeverityLevel severity,
                               AlertStatus status, BigDecimal fraudProbability, BigDecimal ruleScore,
                               OffsetDateTime createdAt, BigDecimal finalScore, Map<String, Object> explanation,
                               OffsetDateTime updatedAt, BigDecimal amount, String currency, String deviceId,
                               String location, String merchantType, OffsetDateTime transactionTimestamp,
                               AlertVerdictSummary verdict) {
        super(id, transactionId, decision, severity, status, fraudProbability, ruleScore, createdAt);
        this.finalScore = finalScore;
        this.explanation = explanation;
        this.updatedAt = updatedAt;
        this.amount = amount;
        this.currency = currency;
        this.deviceId = deviceId;
        this.location = location;
        this.merchantType = merchantType;
        this.transactionTimestamp = transactionTimestamp;
        this.verdict = verdict;
    }

    public BigDecimal getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(BigDecimal finalScore) {
        this.finalScore = finalScore;
    }

    public Map<String, Object> getExplanation() {
        return explanation;
    }

    public void setExplanation(Map<String, Object> explanation) {
        this.explanation = explanation;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getMerchantType() {
        return merchantType;
    }

    public void setMerchantType(String merchantType) {
        this.merchantType = merchantType;
    }

    public OffsetDateTime getTransactionTimestamp() {
        return transactionTimestamp;
    }

    public void setTransactionTimestamp(OffsetDateTime transactionTimestamp) {
        this.transactionTimestamp = transactionTimestamp;
    }

    public AlertVerdictSummary getVerdict() {
        return verdict;
    }

    public void setVerdict(AlertVerdictSummary verdict) {
        this.verdict = verdict;
    }
}
