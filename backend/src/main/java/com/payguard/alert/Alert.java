package com.payguard.alert;

import com.payguard.decision.Decision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "fraud_probability", precision = 5, scale = 4)
    private BigDecimal fraudProbability;

    @Column(name = "rule_score", precision = 5, scale = 2)
    private BigDecimal ruleScore;

    @Column(name = "final_score", precision = 5, scale = 4)
    private BigDecimal finalScore;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "decision", nullable = false, columnDefinition = "alert_decision")
    private Decision decision;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "severity", columnDefinition = "severity_level")
    private SeverityLevel severity;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", nullable = false, columnDefinition = "alert_status")
    private AlertStatus status = AlertStatus.OPEN;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation", columnDefinition = "jsonb")
    private Map<String, Object> explanation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Alert() {
    }

    public Alert(UUID id, UUID transactionId, BigDecimal fraudProbability, BigDecimal ruleScore,
                 BigDecimal finalScore, Decision decision, SeverityLevel severity, AlertStatus status,
                 Map<String, Object> explanation, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.transactionId = transactionId;
        this.fraudProbability = fraudProbability;
        this.ruleScore = ruleScore;
        this.finalScore = finalScore;
        this.decision = decision;
        this.severity = severity;
        this.status = status != null ? status : AlertStatus.OPEN;
        this.explanation = explanation;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
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

    public BigDecimal getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(BigDecimal finalScore) {
        this.finalScore = finalScore;
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

    public Map<String, Object> getExplanation() {
        return explanation;
    }

    public void setExplanation(Map<String, Object> explanation) {
        this.explanation = explanation;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Alert alert = (Alert) o;
        return Objects.equals(id, alert.id) && Objects.equals(transactionId, alert.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, transactionId);
    }
}
