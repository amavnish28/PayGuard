package com.payguard.retraining.dto;

public class ModelMetricsSummary {

    private Double testPrAuc;
    private Double testRocAuc;
    private Double valPrAuc;
    private Double valRocAuc;
    private Double valBrier;
    private Double blockThreshold;
    private Double reviewThreshold;

    public ModelMetricsSummary() {
    }

    public ModelMetricsSummary(Double testPrAuc, Double testRocAuc, Double valPrAuc,
                               Double valRocAuc, Double valBrier,
                               Double blockThreshold, Double reviewThreshold) {
        this.testPrAuc = testPrAuc;
        this.testRocAuc = testRocAuc;
        this.valPrAuc = valPrAuc;
        this.valRocAuc = valRocAuc;
        this.valBrier = valBrier;
        this.blockThreshold = blockThreshold;
        this.reviewThreshold = reviewThreshold;
    }

    public Double getTestPrAuc() {
        return testPrAuc;
    }

    public void setTestPrAuc(Double testPrAuc) {
        this.testPrAuc = testPrAuc;
    }

    public Double getTestRocAuc() {
        return testRocAuc;
    }

    public void setTestRocAuc(Double testRocAuc) {
        this.testRocAuc = testRocAuc;
    }

    public Double getValPrAuc() {
        return valPrAuc;
    }

    public void setValPrAuc(Double valPrAuc) {
        this.valPrAuc = valPrAuc;
    }

    public Double getValRocAuc() {
        return valRocAuc;
    }

    public void setValRocAuc(Double valRocAuc) {
        this.valRocAuc = valRocAuc;
    }

    public Double getValBrier() {
        return valBrier;
    }

    public void setValBrier(Double valBrier) {
        this.valBrier = valBrier;
    }

    public Double getBlockThreshold() {
        return blockThreshold;
    }

    public void setBlockThreshold(Double blockThreshold) {
        this.blockThreshold = blockThreshold;
    }

    public Double getReviewThreshold() {
        return reviewThreshold;
    }

    public void setReviewThreshold(Double reviewThreshold) {
        this.reviewThreshold = reviewThreshold;
    }
}
