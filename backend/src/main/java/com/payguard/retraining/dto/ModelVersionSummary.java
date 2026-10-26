package com.payguard.retraining.dto;

import java.time.OffsetDateTime;

public class ModelVersionSummary {

    private String version;
    private String modelName;
    private String algorithm;
    private boolean active;
    private OffsetDateTime createdAt;
    private ModelMetricsSummary metrics;

    public ModelVersionSummary() {
    }

    public ModelVersionSummary(String version, String modelName, String algorithm,
                               boolean active, OffsetDateTime createdAt,
                               ModelMetricsSummary metrics) {
        this.version = version;
        this.modelName = modelName;
        this.algorithm = algorithm;
        this.active = active;
        this.createdAt = createdAt;
        this.metrics = metrics;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public ModelMetricsSummary getMetrics() {
        return metrics;
    }

    public void setMetrics(ModelMetricsSummary metrics) {
        this.metrics = metrics;
    }
}
