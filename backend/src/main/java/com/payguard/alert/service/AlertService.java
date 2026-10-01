package com.payguard.alert.service;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.alert.dto.AlertDetailResponse;
import com.payguard.alert.dto.AlertSummaryResponse;
import com.payguard.alert.dto.AlertVerdictSummary;
import com.payguard.decision.Decision;
import com.payguard.exception.AlertNotFoundException;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.verdict.AnalystVerdictRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AlertService {

    private final AlertRepository alertRepository;
    private final TransactionRepository transactionRepository;
    private final AnalystVerdictRepository analystVerdictRepository;
    private final UserRepository userRepository;

    public AlertService(AlertRepository alertRepository,
                        TransactionRepository transactionRepository,
                        AnalystVerdictRepository analystVerdictRepository,
                        UserRepository userRepository) {
        this.alertRepository = alertRepository;
        this.transactionRepository = transactionRepository;
        this.analystVerdictRepository = analystVerdictRepository;
        this.userRepository = userRepository;
    }

    public Page<AlertSummaryResponse> getAlerts(int page, int size, AlertStatus status, Decision decision) {
        int validatedSize = Math.max(1, Math.min(size, 100));
        int validatedPage = Math.max(0, page);
        Pageable pageable = PageRequest.of(validatedPage, validatedSize);

        if (status != null && decision != null) {
            return alertRepository.findAlertSummariesByStatusAndDecision(status, decision, pageable);
        } else if (status != null) {
            return alertRepository.findAlertSummariesByStatus(status, pageable);
        } else if (decision != null) {
            return alertRepository.findAlertSummariesByDecision(decision, pageable);
        } else {
            return alertRepository.findAllAlertSummaries(pageable);
        }
    }

    public AlertDetailResponse getAlertById(UUID id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new AlertNotFoundException("Alert not found with id: " + id));

        Transaction transaction = transactionRepository.findById(alert.getTransactionId())
                .orElseThrow(() -> new IllegalStateException("Linked transaction not found for alert: " + id));

        AlertVerdictSummary verdictSummary = analystVerdictRepository.findByAlertId(id)
                .map(v -> {
                    String analystUsername = userRepository.findById(v.getAnalystId())
                            .map(User::getUsername)
                            .orElse("unknown");
                    return new AlertVerdictSummary(
                            v.getId(),
                            v.getVerdict(),
                            v.getComment(),
                            analystUsername,
                            v.getCreatedAt()
                    );
                })
                .orElse(null);

        AlertDetailResponse response = new AlertDetailResponse(
                alert.getId(),
                transaction.getTransactionId(),
                alert.getDecision(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getFraudProbability(),
                alert.getRuleScore(),
                alert.getCreatedAt(),
                alert.getFinalScore(),
                alert.getExplanation(),
                alert.getUpdatedAt(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDeviceId(),
                transaction.getLocation(),
                transaction.getMerchantType(),
                transaction.getTransactionTimestamp(),
                verdictSummary
        );
        return response;
    }
}
