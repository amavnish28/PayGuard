package com.payguard.retraining;

import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import com.payguard.retraining.dto.ModelVersionSummary;
import com.payguard.retraining.dto.RetrainingEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class ModelController {

    private static final List<String> RETRAINING_ACTIONS = Arrays.asList(
            "MODEL_RETRAIN_ATTEMPTED",
            "MODEL_RECONCILED",
            "MODEL_ACTIVATION_MISMATCH_CRITICAL"
    );

    private final ModelVersionRepository modelVersionRepository;
    private final AuditLogRepository auditLogRepository;

    public ModelController(ModelVersionRepository modelVersionRepository,
                           AuditLogRepository auditLogRepository) {
        this.modelVersionRepository = modelVersionRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/models")
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public ResponseEntity<List<ModelVersionSummary>> getModels() {
        List<ModelVersion> versions = modelVersionRepository.findAllByOrderByCreatedAtDesc();
        List<ModelVersionSummary> summaries = versions.stream()
                .map(mv -> new ModelVersionSummary(
                        mv.getVersion(),
                        mv.getModelName(),
                        mv.getAlgorithm(),
                        Boolean.TRUE.equals(mv.getIsActive()),
                        mv.getCreatedAt(),
                        ModelMetricsExtractor.extractMetrics(mv.getMetrics())
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/admin/retraining-history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<RetrainingEvent>> getRetrainingHistory(
            @RequestParam(name = "limit", defaultValue = "50") Integer limit) {
        if (limit == null || limit < 1 || limit > 200) {
            return ResponseEntity.badRequest().build();
        }
        List<AuditLog> logs = auditLogRepository.findByActionInOrderByCreatedAtDesc(
                RETRAINING_ACTIONS,
                PageRequest.of(0, limit)
        );
        List<RetrainingEvent> events = logs.stream()
                .map(ModelMetricsExtractor::extractRetrainingEvent)
                .collect(Collectors.toList());
        return ResponseEntity.ok(events);
    }
}
