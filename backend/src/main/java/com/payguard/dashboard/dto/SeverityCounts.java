package com.payguard.dashboard.dto;

public record SeverityCounts(
        long low,
        long medium,
        long high,
        long critical,
        long unclassified
) {
}
