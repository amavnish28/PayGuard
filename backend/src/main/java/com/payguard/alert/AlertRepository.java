package com.payguard.alert;

import com.payguard.alert.dto.AlertSummaryResponse;
import com.payguard.decision.Decision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    Optional<Alert> findByTransactionId(UUID transactionId);

    @Query(value = "SELECT new com.payguard.alert.dto.AlertSummaryResponse(" +
            "a.id, t.transactionId, a.decision, a.severity, a.status, a.fraudProbability, a.ruleScore, a.createdAt) " +
            "FROM Alert a JOIN Transaction t ON a.transactionId = t.id " +
            "ORDER BY a.createdAt DESC",
            countQuery = "SELECT count(a) FROM Alert a")
    Page<AlertSummaryResponse> findAllAlertSummaries(Pageable pageable);

    @Query(value = "SELECT new com.payguard.alert.dto.AlertSummaryResponse(" +
            "a.id, t.transactionId, a.decision, a.severity, a.status, a.fraudProbability, a.ruleScore, a.createdAt) " +
            "FROM Alert a JOIN Transaction t ON a.transactionId = t.id " +
            "WHERE a.status = :status " +
            "ORDER BY a.createdAt DESC",
            countQuery = "SELECT count(a) FROM Alert a WHERE a.status = :status")
    Page<AlertSummaryResponse> findAlertSummariesByStatus(
            @Param("status") AlertStatus status,
            Pageable pageable);

    @Query(value = "SELECT new com.payguard.alert.dto.AlertSummaryResponse(" +
            "a.id, t.transactionId, a.decision, a.severity, a.status, a.fraudProbability, a.ruleScore, a.createdAt) " +
            "FROM Alert a JOIN Transaction t ON a.transactionId = t.id " +
            "WHERE a.decision = :decision " +
            "ORDER BY a.createdAt DESC",
            countQuery = "SELECT count(a) FROM Alert a WHERE a.decision = :decision")
    Page<AlertSummaryResponse> findAlertSummariesByDecision(
            @Param("decision") Decision decision,
            Pageable pageable);

    @Query(value = "SELECT new com.payguard.alert.dto.AlertSummaryResponse(" +
            "a.id, t.transactionId, a.decision, a.severity, a.status, a.fraudProbability, a.ruleScore, a.createdAt) " +
            "FROM Alert a JOIN Transaction t ON a.transactionId = t.id " +
            "WHERE a.status = :status AND a.decision = :decision " +
            "ORDER BY a.createdAt DESC",
            countQuery = "SELECT count(a) FROM Alert a WHERE a.status = :status AND a.decision = :decision")
    Page<AlertSummaryResponse> findAlertSummariesByStatusAndDecision(
            @Param("status") AlertStatus status,
            @Param("decision") Decision decision,
            Pageable pageable);
}
