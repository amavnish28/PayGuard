package com.payguard.retraining;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModelVersionRepository extends JpaRepository<ModelVersion, UUID> {

    Optional<ModelVersion> findByVersion(String version);

    Optional<ModelVersion> findFirstByIsActiveTrueOrderByCreatedAtDesc();

    List<ModelVersion> findAllByIsActiveTrue();

    @Modifying
    @Query("UPDATE ModelVersion m SET m.isActive = false WHERE m.isActive = true")
    int deactivateAll();
}
