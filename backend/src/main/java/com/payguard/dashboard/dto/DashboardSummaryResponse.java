package com.payguard.dashboard.dto;

public record DashboardSummaryResponse(
        long totalTransactions,
        long totalAlerts,
        DecisionCounts decisionCounts,
        StatusCounts statusCounts,
        SeverityCounts severityCounts,
        WindowedStats windowed,
        Double mlAvailabilityRate
) {
}
