package com.payguard.verdict;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AnalystVerdictRepository extends JpaRepository<AnalystVerdict, UUID> {

    boolean existsByAlertId(UUID alertId);

    Optional<AnalystVerdict> findByAlertId(UUID alertId);
}
