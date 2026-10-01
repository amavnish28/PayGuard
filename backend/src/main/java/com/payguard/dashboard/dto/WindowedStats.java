package com.payguard.dashboard.dto;

import java.time.OffsetDateTime;

public record WindowedStats(
        OffsetDateTime sinceTimestamp,
        long transactionsInWindow,
        long alertsInWindow,
        long blockCountInWindow,
        long reviewCountInWindow
) {
}
