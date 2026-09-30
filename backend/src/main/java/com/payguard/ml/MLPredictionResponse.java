package com.payguard.ml;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class MLPredictionResponse {

    @JsonProperty("fraud_probability")
    private Double fraudProbability;

    @JsonProperty("model_version")
    private String modelVersion;

    @JsonProperty("review_threshold")
    private Double reviewThreshold;

    @JsonProperty("block_threshold")
    private Double blockThreshold;

    @JsonProperty("ml_band")
    private String mlBand;

    @JsonProperty("shap_reasons")
    private List<ShapReason> shapReasons;

    @JsonProperty("latency_ms")
    private Double latencyMs;

    public MLPredictionResponse() {
    }

    public MLPredictionResponse(Double fraudProbability, String modelVersion, Double reviewThreshold,
                                Double blockThreshold, String mlBand, List<ShapReason> shapReasons, Double latencyMs) {
        this.fraudProbability = fraudProbability;
        this.modelVersion = modelVersion;
        this.reviewThreshold = reviewThreshold;
        this.blockThreshold = blockThreshold;
        this.mlBand = mlBand;
        this.shapReasons = shapReasons;
        this.latencyMs = latencyMs;
    }

    public Double getFraudProbability() {
        return fraudProbability;
    }

    public void setFraudProbability(Double fraudProbability) {
        this.fraudProbability = fraudProbability;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public Double getReviewThreshold() {
        return reviewThreshold;
    }

    public void setReviewThreshold(Double reviewThreshold) {
        this.reviewThreshold = reviewThreshold;
    }

    public Double getBlockThreshold() {
        return blockThreshold;
    }

    public void setBlockThreshold(Double blockThreshold) {
        this.blockThreshold = blockThreshold;
    }

    public String getMlBand() {
        return mlBand;
    }

    public void setMlBand(String mlBand) {
        this.mlBand = mlBand;
    }

    public List<ShapReason> getShapReasons() {
        return shapReasons;
    }

    public void setShapReasons(List<ShapReason> shapReasons) {
        this.shapReasons = shapReasons;
    }

    public Double getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Double latencyMs) {
        this.latencyMs = latencyMs;
    }
}
