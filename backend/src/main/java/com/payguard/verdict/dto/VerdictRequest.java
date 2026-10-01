package com.payguard.verdict.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.payguard.verdict.VerdictType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VerdictRequest {

    @NotNull(message = "Verdict is required")
    private VerdictType verdict;

    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    private String comment;

    public VerdictRequest() {
    }

    public VerdictRequest(VerdictType verdict, String comment) {
        this.verdict = verdict;
        this.comment = comment;
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
}
