package com.payguard.verdict;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AnalystVerdictRepository extends JpaRepository<AnalystVerdict, UUID> {

    boolean existsByAlertId(UUID alertId);

    Optional<AnalystVerdict> findByAlertId(UUID alertId);

    @Query("""
        SELECT v, a, t, tf
        FROM AnalystVerdict v, Alert a, Transaction t, TransactionFeature tf
        WHERE v.alertId = a.id
          AND a.transactionId = t.id
          AND tf.transactionRefId = t.id
        ORDER BY v.createdAt ASC
    """)
    List<Object[]> findAllVerdictedRecords();
}
