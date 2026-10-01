package com.payguard.dashboard.dto;

public record DecisionCounts(
        long approve,
        long review,
        long block
) {
}
