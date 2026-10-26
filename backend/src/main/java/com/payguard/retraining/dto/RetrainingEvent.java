package com.payguard.retraining.dto;

import java.time.OffsetDateTime;

public class RetrainingEvent {

    private String action;
    private String entityId;
    private OffsetDateTime createdAt;
    private Boolean eligible;
    private Boolean activationGatePassed;
    private String reason;
    private String activeBefore;
    private String activeAfter;
    private Integer totalVerdicts;
    private Integer fraudCount;
    private Integer legitimateCount;
    private Double candidatePrAuc;

    public RetrainingEvent() {
    }

    public RetrainingEvent(String action, String entityId, OffsetDateTime createdAt,
                           Boolean eligible, Boolean activationGatePassed, String reason,
                           String activeBefore, String activeAfter, Integer totalVerdicts,
                           Integer fraudCount, Integer legitimateCount, Double candidatePrAuc) {
        this.action = action;
        this.entityId = entityId;
        this.createdAt = createdAt;
        this.eligible = eligible;
        this.activationGatePassed = activationGatePassed;
        this.reason = reason;
        this.activeBefore = activeBefore;
        this.activeAfter = activeAfter;
        this.totalVerdicts = totalVerdicts;
        this.fraudCount = fraudCount;
        this.legitimateCount = legitimateCount;
        this.candidatePrAuc = candidatePrAuc;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getEligible() {
        return eligible;
    }

    public void setEligible(Boolean eligible) {
        this.eligible = eligible;
    }

    public Boolean getActivationGatePassed() {
        return activationGatePassed;
    }

    public void setActivationGatePassed(Boolean activationGatePassed) {
        this.activationGatePassed = activationGatePassed;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActiveBefore() {
        return activeBefore;
    }

    public void setActiveBefore(String activeBefore) {
        this.activeBefore = activeBefore;
    }

    public String getActiveAfter() {
        return activeAfter;
    }

    public void setActiveAfter(String activeAfter) {
        this.activeAfter = activeAfter;
    }

    public Integer getTotalVerdicts() {
        return totalVerdicts;
    }

    public void setTotalVerdicts(Integer totalVerdicts) {
        this.totalVerdicts = totalVerdicts;
    }

    public Integer getFraudCount() {
        return fraudCount;
    }

    public void setFraudCount(Integer fraudCount) {
        this.fraudCount = fraudCount;
    }

    public Integer getLegitimateCount() {
        return legitimateCount;
    }

    public void setLegitimateCount(Integer legitimateCount) {
        this.legitimateCount = legitimateCount;
    }

    public Double getCandidatePrAuc() {
        return candidatePrAuc;
    }

    public void setCandidatePrAuc(Double candidatePrAuc) {
        this.candidatePrAuc = candidatePrAuc;
    }
}
