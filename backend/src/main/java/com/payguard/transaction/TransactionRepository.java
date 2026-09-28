package com.payguard.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:accountId))", nativeQuery = true)
    Object acquireAccountAdvisoryLock(@Param("accountId") String accountId);

    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.id <> :currentId " +
           "AND t.transactionTimestamp >= :since " +
           "AND t.transactionTimestamp <= :currentTimestamp")
    long countPreviousTransactionsInRange(
            @Param("accountId") String accountId,
            @Param("currentId") UUID currentId,
            @Param("since") OffsetDateTime since,
            @Param("currentTimestamp") OffsetDateTime currentTimestamp);

    @Query("SELECT AVG(t.amount) FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.id <> :currentId " +
           "AND t.transactionTimestamp <= :currentTimestamp")
    BigDecimal findHistoricalAverageAmount(
            @Param("accountId") String accountId,
            @Param("currentId") UUID currentId,
            @Param("currentTimestamp") OffsetDateTime currentTimestamp);

    @Query("SELECT t FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.id <> :currentId " +
           "AND t.transactionTimestamp <= :currentTimestamp " +
           "ORDER BY t.transactionTimestamp DESC, t.createdAt DESC")
    List<Transaction> findPreviousTransactions(
            @Param("accountId") String accountId,
            @Param("currentId") UUID currentId,
            @Param("currentTimestamp") OffsetDateTime currentTimestamp,
            Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.id <> :currentId " +
           "AND t.deviceId = :deviceId " +
           "AND t.transactionTimestamp <= :currentTimestamp")
    boolean isDevicePreviouslyUsed(
            @Param("accountId") String accountId,
            @Param("currentId") UUID currentId,
            @Param("deviceId") String deviceId,
            @Param("currentTimestamp") OffsetDateTime currentTimestamp);

    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.id <> :currentId " +
           "AND t.location = :location " +
           "AND t.transactionTimestamp <= :currentTimestamp")
    boolean isLocationPreviouslyUsed(
            @Param("accountId") String accountId,
            @Param("currentId") UUID currentId,
            @Param("location") String location,
            @Param("currentTimestamp") OffsetDateTime currentTimestamp);

    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Transaction t " +
           "WHERE t.accountId = :accountId " +
           "AND t.id <> :currentId " +
           "AND t.transactionTimestamp <= :currentTimestamp")
    boolean hasPreviousTransactions(
            @Param("accountId") String accountId,
            @Param("currentId") UUID currentId,
            @Param("currentTimestamp") OffsetDateTime currentTimestamp);
}
