package com.payguard.alert;

import com.payguard.alert.dto.AlertDetailResponse;
import com.payguard.alert.dto.AlertSummaryResponse;
import com.payguard.alert.service.AlertService;
import com.payguard.decision.Decision;
import com.payguard.exception.AlertNotFoundException;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private AlertService alertService;

    private UUID alertId;
    private UUID txnUuid;
    private Alert sampleAlert;
    private Transaction sampleTxn;

    @BeforeEach
    void setUp() {
        alertId = UUID.randomUUID();
        txnUuid = UUID.randomUUID();

        sampleTxn = new Transaction();
        sampleTxn.setId(txnUuid);
        sampleTxn.setTransactionId("TXN-12345");
        sampleTxn.setAccountId("ACC-999");
        sampleTxn.setAmount(new BigDecimal("250.00"));
        sampleTxn.setCurrency("INR");
        sampleTxn.setDeviceId("device-1");
        sampleTxn.setLocation("Bangalore");
        sampleTxn.setMerchantType("grocery");
        sampleTxn.setTransactionTimestamp(OffsetDateTime.now());

        sampleAlert = new Alert();
        sampleAlert.setId(alertId);
        sampleAlert.setTransactionId(txnUuid);
        sampleAlert.setDecision(Decision.REVIEW);
        sampleAlert.setSeverity(SeverityLevel.LOW);
        sampleAlert.setStatus(AlertStatus.OPEN);
        sampleAlert.setFraudProbability(new BigDecimal("0.5500"));
        sampleAlert.setRuleScore(new BigDecimal("25.00"));
        sampleAlert.setFinalScore(new BigDecimal("0.4500"));
        sampleAlert.setExplanation(Map.of("ruleTier", "LOW"));
        sampleAlert.setCreatedAt(OffsetDateTime.now());
        sampleAlert.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    @DisplayName("getAlerts without filters delegates to findAllAlertSummaries")
    void testGetAlertsNoFilters() {
        AlertSummaryResponse summary = new AlertSummaryResponse(
                alertId, "TXN-12345", Decision.REVIEW, SeverityLevel.LOW,
                AlertStatus.OPEN, new BigDecimal("0.5500"), new BigDecimal("25.00"), OffsetDateTime.now()
        );
        Page<AlertSummaryResponse> page = new PageImpl<>(List.of(summary));
        when(alertRepository.findAllAlertSummaries(any(Pageable.class))).thenReturn(page);

        Page<AlertSummaryResponse> result = alertService.getAlerts(0, 20, null, null);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        verify(alertRepository, times(1)).findAllAlertSummaries(any(Pageable.class));
    }

    @Test
    @DisplayName("getAlerts with status filter delegates to findAlertSummariesByStatus")
    void testGetAlertsStatusFilter() {
        Page<AlertSummaryResponse> page = new PageImpl<>(List.of());
        when(alertRepository.findAlertSummariesByStatus(eq(AlertStatus.OPEN), any(Pageable.class))).thenReturn(page);

        Page<AlertSummaryResponse> result = alertService.getAlerts(0, 20, AlertStatus.OPEN, null);

        assertNotNull(result);
        verify(alertRepository, times(1)).findAlertSummariesByStatus(eq(AlertStatus.OPEN), any(Pageable.class));
    }

    @Test
    @DisplayName("getAlerts with decision filter delegates to findAlertSummariesByDecision")
    void testGetAlertsDecisionFilter() {
        Page<AlertSummaryResponse> page = new PageImpl<>(List.of());
        when(alertRepository.findAlertSummariesByDecision(eq(Decision.BLOCK), any(Pageable.class))).thenReturn(page);

        Page<AlertSummaryResponse> result = alertService.getAlerts(0, 20, null, Decision.BLOCK);

        assertNotNull(result);
        verify(alertRepository, times(1)).findAlertSummariesByDecision(eq(Decision.BLOCK), any(Pageable.class));
    }

    @Test
    @DisplayName("getAlerts with both filters delegates to findAlertSummariesByStatusAndDecision")
    void testGetAlertsBothFilters() {
        Page<AlertSummaryResponse> page = new PageImpl<>(List.of());
        when(alertRepository.findAlertSummariesByStatusAndDecision(eq(AlertStatus.OPEN), eq(Decision.BLOCK), any(Pageable.class))).thenReturn(page);

        Page<AlertSummaryResponse> result = alertService.getAlerts(0, 20, AlertStatus.OPEN, Decision.BLOCK);

        assertNotNull(result);
        verify(alertRepository, times(1)).findAlertSummariesByStatusAndDecision(eq(AlertStatus.OPEN), eq(Decision.BLOCK), any(Pageable.class));
    }

    @Test
    @DisplayName("getAlertById returns mapped AlertDetailResponse when alert and transaction exist")
    void testGetAlertByIdSuccess() {
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(sampleAlert));
        when(transactionRepository.findById(txnUuid)).thenReturn(Optional.of(sampleTxn));

        AlertDetailResponse detail = alertService.getAlertById(alertId);

        assertNotNull(detail);
        assertEquals(alertId, detail.getId());
        assertEquals("TXN-12345", detail.getTransactionId());
        assertEquals(Decision.REVIEW, detail.getDecision());
        assertEquals(SeverityLevel.LOW, detail.getSeverity());
        assertEquals(AlertStatus.OPEN, detail.getStatus());
        assertEquals(new BigDecimal("0.5500"), detail.getFraudProbability());
        assertEquals(new BigDecimal("25.00"), detail.getRuleScore());
        assertEquals(new BigDecimal("0.4500"), detail.getFinalScore());
        assertEquals("LOW", detail.getExplanation().get("ruleTier"));
        assertEquals(new BigDecimal("250.00"), detail.getAmount());
        assertEquals("INR", detail.getCurrency());
        assertEquals("device-1", detail.getDeviceId());
        assertEquals("Bangalore", detail.getLocation());
        assertEquals("grocery", detail.getMerchantType());
    }

    @Test
    @DisplayName("getAlertById throws AlertNotFoundException when alert does not exist")
    void testGetAlertByIdNotFound() {
        when(alertRepository.findById(alertId)).thenReturn(Optional.empty());

        assertThrows(AlertNotFoundException.class, () -> alertService.getAlertById(alertId));
        verify(transactionRepository, never()).findById(any());
    }
}
