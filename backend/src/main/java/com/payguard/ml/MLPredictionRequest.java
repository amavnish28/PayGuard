package com.payguard.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "amount",
        "transactions_last_2_min",
        "transactions_last_1_hour",
        "account_avg_amount",
        "amount_ratio",
        "time_since_previous_seconds",
        "is_first_transaction",
        "new_device",
        "new_location",
        "transaction_hour",
        "odd_hour"
})
public class MLPredictionRequest {

    @JsonProperty("amount")
    private Double amount;

    @JsonProperty("transactions_last_2_min")
    private Integer transactionsLast2Min;

    @JsonProperty("transactions_last_1_hour")
    private Integer transactionsLast1Hour;

    @JsonProperty("account_avg_amount")
    private Double accountAvgAmount;

    @JsonProperty("amount_ratio")
    private Double amountRatio;

    @JsonProperty("time_since_previous_seconds")
    private Double timeSincePreviousSeconds;

    @JsonProperty("is_first_transaction")
    private Integer isFirstTransaction;

    @JsonProperty("new_device")
    private Integer newDevice;

    @JsonProperty("new_location")
    private Integer newLocation;

    @JsonProperty("transaction_hour")
    private Integer transactionHour;

    @JsonProperty("odd_hour")
    private Integer oddHour;

    public MLPredictionRequest() {
    }

    public MLPredictionRequest(Double amount,
                               Integer transactionsLast2Min,
                               Integer transactionsLast1Hour,
                               Double accountAvgAmount,
                               Double amountRatio,
                               Double timeSincePreviousSeconds,
                               Integer isFirstTransaction,
                               Integer newDevice,
                               Integer newLocation,
                               Integer transactionHour,
                               Integer oddHour) {
        this.amount = amount;
        this.transactionsLast2Min = transactionsLast2Min;
        this.transactionsLast1Hour = transactionsLast1Hour;
        this.accountAvgAmount = accountAvgAmount;
        this.amountRatio = amountRatio;
        this.timeSincePreviousSeconds = timeSincePreviousSeconds;
        this.isFirstTransaction = isFirstTransaction;
        this.newDevice = newDevice;
        this.newLocation = newLocation;
        this.transactionHour = transactionHour;
        this.oddHour = oddHour;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
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

    public Double getAccountAvgAmount() {
        return accountAvgAmount;
    }

    public void setAccountAvgAmount(Double accountAvgAmount) {
        this.accountAvgAmount = accountAvgAmount;
    }

    public Double getAmountRatio() {
        return amountRatio;
    }

    public void setAmountRatio(Double amountRatio) {
        this.amountRatio = amountRatio;
    }

    public Double getTimeSincePreviousSeconds() {
        return timeSincePreviousSeconds;
    }

    public void setTimeSincePreviousSeconds(Double timeSincePreviousSeconds) {
        this.timeSincePreviousSeconds = timeSincePreviousSeconds;
    }

    public Integer getIsFirstTransaction() {
        return isFirstTransaction;
    }

    public void setIsFirstTransaction(Integer isFirstTransaction) {
        this.isFirstTransaction = isFirstTransaction;
    }

    public Integer getNewDevice() {
        return newDevice;
    }

    public void setNewDevice(Integer newDevice) {
        this.newDevice = newDevice;
    }

    public Integer getNewLocation() {
        return newLocation;
    }

    public void setNewLocation(Integer newLocation) {
        this.newLocation = newLocation;
    }

    public Integer getTransactionHour() {
        return transactionHour;
    }

    public void setTransactionHour(Integer transactionHour) {
        this.transactionHour = transactionHour;
    }

    public Integer getOddHour() {
        return oddHour;
    }

    public void setOddHour(Integer oddHour) {
        this.oddHour = oddHour;
    }
}
