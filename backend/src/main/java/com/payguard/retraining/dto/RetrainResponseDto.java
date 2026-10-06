package com.payguard.retraining.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RetrainResponseDto {

    @JsonProperty("model_version_candidate")
    private String modelVersionCandidate;

    @JsonProperty("metrics")
    private Map<String, Object> metrics;

    @JsonProperty("training_data_summary")
    private Map<String, Object> trainingDataSummary;

    @JsonProperty("model_store_path")
    private String modelStorePath;

    @JsonProperty("block_threshold")
    private Double blockThreshold;

    @JsonProperty("review_threshold")
    private Double reviewThreshold;

    public RetrainResponseDto() {
    }

    public RetrainResponseDto(String modelVersionCandidate, Map<String, Object> metrics,
                              Map<String, Object> trainingDataSummary, String modelStorePath,
                              Double blockThreshold, Double reviewThreshold) {
        this.modelVersionCandidate = modelVersionCandidate;
        this.metrics = metrics;
        this.trainingDataSummary = trainingDataSummary;
        this.modelStorePath = modelStorePath;
        this.blockThreshold = blockThreshold;
        this.reviewThreshold = reviewThreshold;
    }

    public String getModelVersionCandidate() {
        return modelVersionCandidate;
    }

    public void setModelVersionCandidate(String modelVersionCandidate) {
        this.modelVersionCandidate = modelVersionCandidate;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }

    public Map<String, Object> getTrainingDataSummary() {
        return trainingDataSummary;
    }

    public void setTrainingDataSummary(Map<String, Object> trainingDataSummary) {
        this.trainingDataSummary = trainingDataSummary;
    }

    public String getModelStorePath() {
        return modelStorePath;
    }

    public void setModelStorePath(String modelStorePath) {
        this.modelStorePath = modelStorePath;
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
