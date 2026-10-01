package com.payguard.alert.dto;

import com.payguard.verdict.VerdictType;

import java.time.OffsetDateTime;
import java.util.UUID;

public class AlertVerdictSummary {

    private UUID id;
    private VerdictType verdict;
    private String comment;
    private String analystUsername;
    private OffsetDateTime createdAt;

    public AlertVerdictSummary() {
    }

    public AlertVerdictSummary(UUID id, VerdictType verdict, String comment, String analystUsername, OffsetDateTime createdAt) {
        this.id = id;
        this.verdict = verdict;
        this.comment = comment;
        this.analystUsername = analystUsername;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public VerdictType getVerdict() {
        return verdict;
    }

    public void setVerdict(VerdictType verdict) {
        this.verdict = verdict;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getAnalystUsername() {
        return analystUsername;
    }

    public void setAnalystUsername(String analystUsername) {
        this.analystUsername = analystUsername;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
