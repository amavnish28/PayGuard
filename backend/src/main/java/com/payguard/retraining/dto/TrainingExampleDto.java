package com.payguard.retraining.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class TrainingExampleDto {

    @JsonProperty("features")
    private Map<String, Object> features;

    @JsonProperty("label")
    private int label;

    public TrainingExampleDto() {
    }

    public TrainingExampleDto(Map<String, Object> features, int label) {
        this.features = features;
        this.label = label;
    }

    public Map<String, Object> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Object> features) {
        this.features = features;
    }

    public int getLabel() {
        return label;
    }

    public void setLabel(int label) {
        this.label = label;
    }
}
