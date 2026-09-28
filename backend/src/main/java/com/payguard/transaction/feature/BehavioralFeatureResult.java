package com.payguard.transaction.feature;

import java.math.BigDecimal;

public class BehavioralFeatureResult {

    private final Integer transactionsLast2Min;
    private final Integer transactionsLast1Hour;
    private final BigDecimal accountAvgAmount;
    private final BigDecimal amountRatio;
    private final Long timeSincePreviousTransactionSeconds;
    private final Boolean newDevice;
    private final Boolean newLocation;
    private final Short transactionHour;
    private final Boolean oddHour;
    private final boolean hasPreviousTransactions;

    public BehavioralFeatureResult(Integer transactionsLast2Min,
                                   Integer transactionsLast1Hour,
                                   BigDecimal accountAvgAmount,
                                   BigDecimal amountRatio,
                                   Long timeSincePreviousTransactionSeconds,
                                   Boolean newDevice,
                                   Boolean newLocation,
                                   Short transactionHour,
                                   Boolean oddHour,
                                   boolean hasPreviousTransactions) {
        this.transactionsLast2Min = transactionsLast2Min;
        this.transactionsLast1Hour = transactionsLast1Hour;
        this.accountAvgAmount = accountAvgAmount;
        this.amountRatio = amountRatio;
        this.timeSincePreviousTransactionSeconds = timeSincePreviousTransactionSeconds;
        this.newDevice = newDevice;
        this.newLocation = newLocation;
        this.transactionHour = transactionHour;
        this.oddHour = oddHour;
        this.hasPreviousTransactions = hasPreviousTransactions;
    }

    public Integer getTransactionsLast2Min() {
        return transactionsLast2Min;
    }

    public Integer getTransactionsLast1Hour() {
        return transactionsLast1Hour;
    }

    public BigDecimal getAccountAvgAmount() {
        return accountAvgAmount;
    }

    public BigDecimal getAmountRatio() {
        return amountRatio;
    }

    public Long getTimeSincePreviousTransactionSeconds() {
        return timeSincePreviousTransactionSeconds;
    }

    public Boolean getNewDevice() {
        return newDevice;
    }

    public Boolean getNewLocation() {
        return newLocation;
    }

    public Short getTransactionHour() {
        return transactionHour;
    }

    public Boolean getOddHour() {
        return oddHour;
    }

    public boolean hasPreviousTransactions() {
        return hasPreviousTransactions;
    }
}
