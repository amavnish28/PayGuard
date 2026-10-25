package com.payguard.retraining;

import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class ModelStartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(ModelStartupReconciler.class);

    private final ModelVersionRepository modelVersionRepository;
    private final MLRetrainingClient mlRetrainingClient;
    private final AuditLogRepository auditLogRepository;
    private final Environment environment;

    public ModelStartupReconciler(
            ModelVersionRepository modelVersionRepository,
            MLRetrainingClient mlRetrainingClient,
            AuditLogRepository auditLogRepository,
            Environment environment) {
        this.modelVersionRepository = modelVersionRepository;
        this.mlRetrainingClient = mlRetrainingClient;
        this.auditLogRepository = auditLogRepository;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (environment != null && environment.acceptsProfiles(Profiles.of("test"))) {
            log.info("Skipping model startup reconciliation under 'test' profile.");
            return;
        }
        reconcile();
    }

    public boolean reconcile() {
        try {
            Optional<ModelVersion> activeVersionOpt = modelVersionRepository.findFirstByIsActiveTrueOrderByCreatedAtDesc();
            if (activeVersionOpt.isEmpty()) {
                log.info("No active model found in database; skipping startup model reconciliation.");
                return false;
            }

            String dbActive = activeVersionOpt.get().getVersion();

            Map<String, Object> info;
            try {
                info = mlRetrainingClient.getModelInfo();
            } catch (Exception ex) {
                log.warn("FastAPI ML service unreachable during startup model reconciliation: {}. Skipping.", ex.getMessage());
                return false;
            }

            if (info == null || !info.containsKey("model_version")) {
                log.warn("FastAPI ML service returned invalid model info during startup reconciliation: {}. Skipping.", info);
                return false;
            }

            String fastApiWas = String.valueOf(info.get("model_version"));
            if (Objects.equals(dbActive, fastApiWas)) {
                log.info("Startup model reconciliation: DB active version '{}' matches FastAPI version. No reconciliation needed.", dbActive);
                return false;
            }

            log.info("Startup model reconciliation: mismatch detected (DB='{}', FastAPI='{}'). Activating '{}'...",
                    dbActive, fastApiWas, dbActive);

            try {
                mlRetrainingClient.activate(dbActive);
            } catch (Exception ex) {
                log.warn("Failed to activate model '{}' on FastAPI during startup reconciliation: {}. Startup continuing.",
                        dbActive, ex.getMessage());
                return false;
            }

            AuditLog auditLog = new AuditLog();
            auditLog.setAction("MODEL_RECONCILED");
            auditLog.setEntityType("model_version");
            auditLog.setEntityId(dbActive);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("dbActive", dbActive);
            details.put("fastApiWas", fastApiWas);
            auditLog.setDetails(details);
            auditLog.setCreatedAt(OffsetDateTime.now());

            auditLogRepository.saveAndFlush(auditLog);
            log.info("Successfully reconciled model to '{}' and recorded MODEL_RECONCILED audit log entry.", dbActive);
            return true;
        } catch (Exception ex) {
            log.warn("Unexpected exception during startup model reconciliation: {}. Startup continuing.", ex.getMessage(), ex);
            return false;
        }
    }
}
