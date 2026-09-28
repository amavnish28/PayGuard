package com.payguard.transaction.feature;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionFeatureRepository extends JpaRepository<TransactionFeature, UUID> {

    Optional<TransactionFeature> findByTransactionRefId(UUID transactionRefId);

    void deleteByTransactionRefId(UUID transactionRefId);
}
