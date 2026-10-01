package com.payguard.dashboard.service;

import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.dashboard.dto.DashboardSummaryResponse;
import com.payguard.dashboard.dto.DecisionCounts;
import com.payguard.dashboard.dto.SeverityCounts;
import com.payguard.dashboard.dto.StatusCounts;
import com.payguard.dashboard.dto.WindowedStats;
import com.payguard.decision.Decision;
import com.payguard.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final TransactionRepository transactionRepository;
    private final AlertRepository alertRepository;

    public DashboardService(TransactionRepository transactionRepository, AlertRepository alertRepository) {
        this.transactionRepository = transactionRepository;
        this.alertRepository = alertRepository;
    }

    public DashboardSummaryResponse getDashboardSummary(int sinceHours) {
        if (sinceHours <= 0 || sinceHours > 720) {
            throw new IllegalArgumentException("sinceHours must be between 1 and 720");
        }

        // Section 2a: Compute ONE Instant/OffsetDateTime boundary once and reuse across all windowed queries
        OffsetDateTime sinceInstant = OffsetDateTime.now(ZoneOffset.UTC).minusHours(sinceHours);

        long totalTransactions = transactionRepository.count();
        long totalAlerts = alertRepository.count();

        // 1. All-time Decision Counts
        long approve = 0;
        long review = 0;
        long block = 0;
        List<Object[]> decisionGroups = alertRepository.countByDecisionGroup();
        for (Object[] row : decisionGroups) {
            Decision dec = (Decision) row[0];
            long count = (Long) row[1];
            if (dec == Decision.APPROVE) {
                approve = count;
            } else if (dec == Decision.REVIEW) {
                review = count;
            } else if (dec == Decision.BLOCK) {
                block = count;
            }
        }
        DecisionCounts decisionCounts = new DecisionCounts(approve, review, block);

        // 2. All-time Status Counts
        long open = 0;
        long inReview = 0;
        long resolved = 0;
        List<Object[]> statusGroups = alertRepository.countByStatusGroup();
        for (Object[] row : statusGroups) {
            AlertStatus st = (AlertStatus) row[0];
            long count = (Long) row[1];
            if (st == AlertStatus.OPEN) {
                open = count;
            } else if (st == AlertStatus.IN_REVIEW) {
                inReview = count;
            } else if (st == AlertStatus.RESOLVED) {
                resolved = count;
            }
        }
        StatusCounts statusCounts = new StatusCounts(open, inReview, resolved);

        // 3. All-time Severity Counts (guarding against null severity as unclassified)
        long low = 0;
        long medium = 0;
        long high = 0;
        long critical = 0;
        long unclassified = 0;
        List<Object[]> severityGroups = alertRepository.countBySeverityGroup();
        for (Object[] row : severityGroups) {
            SeverityLevel sev = (SeverityLevel) row[0];
            long count = (Long) row[1];
            if (sev == null) {
                unclassified += count;
            } else if (sev == SeverityLevel.LOW) {
                low = count;
            } else if (sev == SeverityLevel.MEDIUM) {
                medium = count;
            } else if (sev == SeverityLevel.HIGH) {
                high = count;
            } else if (sev == SeverityLevel.CRITICAL) {
                critical = count;
            }
        }
        SeverityCounts severityCounts = new SeverityCounts(low, medium, high, critical, unclassified);

        // 4. Windowed Statistics (using the single computed sinceInstant boundary)
        long transactionsInWindow = transactionRepository.countTransactionsSince(sinceInstant);
        long alertsInWindow = alertRepository.countAlertsSince(sinceInstant);

        long blockCountInWindow = 0;
        long reviewCountInWindow = 0;
        List<Object[]> windowedDecisionGroups = alertRepository.countAlertsSinceGroupedByDecision(sinceInstant);
        for (Object[] row : windowedDecisionGroups) {
            Decision dec = (Decision) row[0];
            long count = (Long) row[1];
            if (dec == Decision.BLOCK) {
                blockCountInWindow = count;
            } else if (dec == Decision.REVIEW) {
                reviewCountInWindow = count;
            }
        }

        WindowedStats windowed = new WindowedStats(
                sinceInstant,
                transactionsInWindow,
                alertsInWindow,
                blockCountInWindow,
                reviewCountInWindow
        );

        // 5. ML Availability Rate (null if alertsInWindow is 0)
        Double mlAvailabilityRate = null;
        if (alertsInWindow > 0) {
            long mlAvailableCount = alertRepository.countMlAvailableSince(sinceInstant);
            mlAvailabilityRate = (double) mlAvailableCount / (double) alertsInWindow;
        }

        return new DashboardSummaryResponse(
                totalTransactions,
                totalAlerts,
                decisionCounts,
                statusCounts,
                severityCounts,
                windowed,
                mlAvailabilityRate
        );
    }
}
