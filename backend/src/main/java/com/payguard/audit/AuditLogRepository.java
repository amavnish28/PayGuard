package com.payguard.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByActionAndEntityId(String action, String entityId);

    @Query("SELECT a FROM AuditLog a WHERE a.action IN :actions ORDER BY a.createdAt DESC")
    List<AuditLog> findByActionInOrderByCreatedAtDesc(@Param("actions") Collection<String> actions, Pageable pageable);
}
