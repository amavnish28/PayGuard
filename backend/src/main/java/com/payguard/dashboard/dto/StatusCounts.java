package com.payguard.dashboard.dto;

public record StatusCounts(
        long open,
        long inReview,
        long resolved
) {
}
