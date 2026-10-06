package com.payguard.retraining.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TrainingDataSummary {

    @JsonProperty("totalVerdicts")
    private long totalVerdicts;

    @JsonProperty("fraudCount")
    private long fraudCount;

    @JsonProperty("legitimateCount")
    private long legitimateCount;

    public TrainingDataSummary() {
    }

    public TrainingDataSummary(long totalVerdicts, long fraudCount, long legitimateCount) {
        this.totalVerdicts = totalVerdicts;
        this.fraudCount = fraudCount;
        this.legitimateCount = legitimateCount;
    }

    public long getTotalVerdicts() {
        return totalVerdicts;
    }

    public void setTotalVerdicts(long totalVerdicts) {
        this.totalVerdicts = totalVerdicts;
    }

    public long getFraudCount() {
        return fraudCount;
    }

    public void setFraudCount(long fraudCount) {
        this.fraudCount = fraudCount;
    }

    public long getLegitimateCount() {
        return legitimateCount;
    }

    public void setLegitimateCount(long legitimateCount) {
        this.legitimateCount = legitimateCount;
    }
}
