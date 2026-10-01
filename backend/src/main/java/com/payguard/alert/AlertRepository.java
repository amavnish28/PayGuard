package com.payguard.alert;

import com.payguard.alert.dto.AlertSummaryResponse;
import com.payguard.decision.Decision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
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

    @Query("SELECT a.decision, COUNT(a) FROM Alert a GROUP BY a.decision")
    List<Object[]> countByDecisionGroup();

    @Query("SELECT a.status, COUNT(a) FROM Alert a GROUP BY a.status")
    List<Object[]> countByStatusGroup();

    @Query("SELECT a.severity, COUNT(a) FROM Alert a GROUP BY a.severity")
    List<Object[]> countBySeverityGroup();

    @Query("SELECT COUNT(a) FROM Alert a WHERE a.createdAt >= :since")
    long countAlertsSince(@Param("since") OffsetDateTime since);

    @Query("SELECT a.decision, COUNT(a) FROM Alert a WHERE a.createdAt >= :since GROUP BY a.decision")
    List<Object[]> countAlertsSinceGroupedByDecision(@Param("since") OffsetDateTime since);

    @Query(value = "SELECT COUNT(*) FROM alerts WHERE created_at >= :since AND explanation IS NOT NULL AND explanation->>'mlAvailable' = 'true'", nativeQuery = true)
    long countMlAvailableSince(@Param("since") OffsetDateTime since);
}
