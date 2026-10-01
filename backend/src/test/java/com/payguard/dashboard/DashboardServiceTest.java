package com.payguard.dashboard;

import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.alert.SeverityLevel;
import com.payguard.dashboard.dto.DashboardSummaryResponse;
import com.payguard.dashboard.service.DashboardService;
import com.payguard.decision.Decision;
import com.payguard.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AlertRepository alertRepository;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(transactionRepository, alertRepository);
    }

    @Test
    @DisplayName("getDashboardSummary calculates all-time and windowed metrics correctly with mixed ML availability")
    void testGetDashboardSummary_Success() {
        when(transactionRepository.count()).thenReturn(100L);
        when(alertRepository.count()).thenReturn(10L);

        List<Object[]> decisionGroups = new ArrayList<>();
        decisionGroups.add(new Object[]{Decision.APPROVE, 1L});
        decisionGroups.add(new Object[]{Decision.REVIEW, 6L});
        decisionGroups.add(new Object[]{Decision.BLOCK, 3L});
        when(alertRepository.countByDecisionGroup()).thenReturn(decisionGroups);

        List<Object[]> statusGroups = new ArrayList<>();
        statusGroups.add(new Object[]{AlertStatus.OPEN, 5L});
        statusGroups.add(new Object[]{AlertStatus.IN_REVIEW, 3L});
        statusGroups.add(new Object[]{AlertStatus.RESOLVED, 2L});
        when(alertRepository.countByStatusGroup()).thenReturn(statusGroups);

        List<Object[]> severityGroups = new ArrayList<>();
        severityGroups.add(new Object[]{SeverityLevel.LOW, 4L});
        severityGroups.add(new Object[]{SeverityLevel.MEDIUM, 3L});
        severityGroups.add(new Object[]{SeverityLevel.HIGH, 1L});
        severityGroups.add(new Object[]{SeverityLevel.CRITICAL, 1L});
        severityGroups.add(new Object[]{null, 1L}); // unclassified
        when(alertRepository.countBySeverityGroup()).thenReturn(severityGroups);

        when(transactionRepository.countTransactionsSince(any(OffsetDateTime.class))).thenReturn(40L);
        when(alertRepository.countAlertsSince(any(OffsetDateTime.class))).thenReturn(4L);

        List<Object[]> windowedDecisionGroups = new ArrayList<>();
        windowedDecisionGroups.add(new Object[]{Decision.BLOCK, 1L});
        windowedDecisionGroups.add(new Object[]{Decision.REVIEW, 3L});
        when(alertRepository.countAlertsSinceGroupedByDecision(any(OffsetDateTime.class))).thenReturn(windowedDecisionGroups);

        when(alertRepository.countMlAvailableSince(any(OffsetDateTime.class))).thenReturn(3L);

        DashboardSummaryResponse response = dashboardService.getDashboardSummary(24);

        assertThat(response.totalTransactions()).isEqualTo(100L);
        assertThat(response.totalAlerts()).isEqualTo(10L);

        assertThat(response.decisionCounts().approve()).isEqualTo(1L);
        assertThat(response.decisionCounts().review()).isEqualTo(6L);
        assertThat(response.decisionCounts().block()).isEqualTo(3L);

        assertThat(response.statusCounts().open()).isEqualTo(5L);
        assertThat(response.statusCounts().inReview()).isEqualTo(3L);
        assertThat(response.statusCounts().resolved()).isEqualTo(2L);

        assertThat(response.severityCounts().low()).isEqualTo(4L);
        assertThat(response.severityCounts().medium()).isEqualTo(3L);
        assertThat(response.severityCounts().high()).isEqualTo(1L);
        assertThat(response.severityCounts().critical()).isEqualTo(1L);
        assertThat(response.severityCounts().unclassified()).isEqualTo(1L);

        assertThat(response.windowed().transactionsInWindow()).isEqualTo(40L);
        assertThat(response.windowed().alertsInWindow()).isEqualTo(4L);
        assertThat(response.windowed().blockCountInWindow()).isEqualTo(1L);
        assertThat(response.windowed().reviewCountInWindow()).isEqualTo(3L);
        assertThat(response.windowed().sinceTimestamp()).isNotNull();

        assertThat(response.mlAvailabilityRate()).isEqualTo(0.75); // 3 / 4
    }

    @Test
    @DisplayName("getDashboardSummary returns null mlAvailabilityRate when alertsInWindow is 0")
    void testGetDashboardSummary_ZeroAlertsInWindow_NullMlAvailability() {
        when(transactionRepository.count()).thenReturn(10L);
        when(alertRepository.count()).thenReturn(2L);

        when(alertRepository.countByDecisionGroup()).thenReturn(List.of());
        when(alertRepository.countByStatusGroup()).thenReturn(List.of());
        when(alertRepository.countBySeverityGroup()).thenReturn(List.of());

        when(transactionRepository.countTransactionsSince(any(OffsetDateTime.class))).thenReturn(0L);
        when(alertRepository.countAlertsSince(any(OffsetDateTime.class))).thenReturn(0L);
        when(alertRepository.countAlertsSinceGroupedByDecision(any(OffsetDateTime.class))).thenReturn(List.of());

        DashboardSummaryResponse response = dashboardService.getDashboardSummary(24);

        assertThat(response.windowed().alertsInWindow()).isEqualTo(0L);
        assertThat(response.mlAvailabilityRate()).isNull();
    }

    @Test
    @DisplayName("Invalid sinceHours throws IllegalArgumentException")
    void testInvalidSinceHours_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> dashboardService.getDashboardSummary(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sinceHours must be between 1 and 720");

        assertThatThrownBy(() -> dashboardService.getDashboardSummary(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sinceHours must be between 1 and 720");

        assertThatThrownBy(() -> dashboardService.getDashboardSummary(721))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sinceHours must be between 1 and 720");
    }
}
