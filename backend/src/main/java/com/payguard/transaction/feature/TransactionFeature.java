package com.payguard.transaction.feature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transaction_features")
public class TransactionFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionRefId;

    @Column(name = "transactions_last_2_min")
    private Integer transactionsLast2Min;

    @Column(name = "transactions_last_1_hour")
    private Integer transactionsLast1Hour;

    @Column(name = "account_avg_amount", precision = 15, scale = 2)
    private BigDecimal accountAvgAmount;

    @Column(name = "amount_ratio", precision = 10, scale = 4)
    private BigDecimal amountRatio;

    @Column(name = "time_since_previous_transaction_seconds")
    private Long timeSincePreviousTransactionSeconds;

    @Column(name = "new_device")
    private Boolean newDevice;

    @Column(name = "new_location")
    private Boolean newLocation;

    @Column(name = "transaction_hour")
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Short transactionHour;

    @Column(name = "odd_hour")
    private Boolean oddHour;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public TransactionFeature() {
    }

    public TransactionFeature(UUID id, UUID transactionRefId, Integer transactionsLast2Min,
                              Integer transactionsLast1Hour, BigDecimal accountAvgAmount,
                              BigDecimal amountRatio, Long timeSincePreviousTransactionSeconds,
                              Boolean newDevice, Boolean newLocation, Short transactionHour,
                              Boolean oddHour, OffsetDateTime createdAt) {
        this.id = id;
        this.transactionRefId = transactionRefId;
        this.transactionsLast2Min = transactionsLast2Min;
        this.transactionsLast1Hour = transactionsLast1Hour;
        this.accountAvgAmount = accountAvgAmount;
        this.amountRatio = amountRatio;
        this.timeSincePreviousTransactionSeconds = timeSincePreviousTransactionSeconds;
        this.newDevice = newDevice;
        this.newLocation = newLocation;
        this.transactionHour = transactionHour;
        this.oddHour = oddHour;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTransactionRefId() {
        return transactionRefId;
    }

    public void setTransactionRefId(UUID transactionRefId) {
        this.transactionRefId = transactionRefId;
    }

    public Integer getTransactionsLast2Min() {
        return transactionsLast2Min;
    }

    public void setTransactionsLast2Min(Integer transactionsLast2Min) {
        this.transactionsLast2Min = transactionsLast2Min;
    }

    public Integer getTransactionsLast1Hour() {
        return transactionsLast1Hour;
    }

    public void setTransactionsLast1Hour(Integer transactionsLast1Hour) {
        this.transactionsLast1Hour = transactionsLast1Hour;
    }

    public BigDecimal getAccountAvgAmount() {
        return accountAvgAmount;
    }

    public void setAccountAvgAmount(BigDecimal accountAvgAmount) {
        this.accountAvgAmount = accountAvgAmount;
    }

    public BigDecimal getAmountRatio() {
        return amountRatio;
    }

    public void setAmountRatio(BigDecimal amountRatio) {
        this.amountRatio = amountRatio;
    }

    public Long getTimeSincePreviousTransactionSeconds() {
        return timeSincePreviousTransactionSeconds;
    }

    public void setTimeSincePreviousTransactionSeconds(Long timeSincePreviousTransactionSeconds) {
        this.timeSincePreviousTransactionSeconds = timeSincePreviousTransactionSeconds;
    }

    public Boolean getNewDevice() {
        return newDevice;
    }

    public void setNewDevice(Boolean newDevice) {
        this.newDevice = newDevice;
    }

    public Boolean getNewLocation() {
        return newLocation;
    }

    public void setNewLocation(Boolean newLocation) {
        this.newLocation = newLocation;
    }

    public Short getTransactionHour() {
        return transactionHour;
    }

    public void setTransactionHour(Short transactionHour) {
        this.transactionHour = transactionHour;
    }

    public Boolean getOddHour() {
        return oddHour;
    }

    public void setOddHour(Boolean oddHour) {
        this.oddHour = oddHour;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionFeature that = (TransactionFeature) o;
        return Objects.equals(id, that.id) ||
                (transactionRefId != null && Objects.equals(transactionRefId, that.transactionRefId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, transactionRefId);
    }
}
