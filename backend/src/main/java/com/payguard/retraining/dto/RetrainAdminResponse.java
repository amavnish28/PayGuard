package com.payguard.retraining.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetrainAdminResponse {

    @JsonProperty("eligible")
    private boolean eligible;

    @JsonProperty("candidateVersion")
    private String candidateVersion;

    @JsonProperty("candidateMetrics")
    private Map<String, Object> candidateMetrics;

    @JsonProperty("activated")
    private boolean activated;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("trainingDataSummary")
    private Map<String, Object> trainingDataSummary;

    public RetrainAdminResponse() {
    }

    public RetrainAdminResponse(boolean eligible, String candidateVersion, Map<String, Object> candidateMetrics,
                                boolean activated, String reason, Map<String, Object> trainingDataSummary) {
        this.eligible = eligible;
        this.candidateVersion = candidateVersion;
        this.candidateMetrics = candidateMetrics;
        this.activated = activated;
        this.reason = reason;
        this.trainingDataSummary = trainingDataSummary;
    }

    public boolean isEligible() {
        return eligible;
    }

    public void setEligible(boolean eligible) {
        this.eligible = eligible;
    }

    public String getCandidateVersion() {
        return candidateVersion;
    }

    public void setCandidateVersion(String candidateVersion) {
        this.candidateVersion = candidateVersion;
    }

    public Map<String, Object> getCandidateMetrics() {
        return candidateMetrics;
    }

    public void setCandidateMetrics(Map<String, Object> candidateMetrics) {
        this.candidateMetrics = candidateMetrics;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, Object> getTrainingDataSummary() {
        return trainingDataSummary;
    }

    public void setTrainingDataSummary(Map<String, Object> trainingDataSummary) {
        this.trainingDataSummary = trainingDataSummary;
    }
}
