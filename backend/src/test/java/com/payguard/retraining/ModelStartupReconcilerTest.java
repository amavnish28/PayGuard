package com.payguard.retraining;

import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class ModelStartupReconcilerTest {

    @Autowired
    private ModelStartupReconciler reconciler;

    @Autowired
    private ModelVersionRepository modelVersionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private MLRetrainingClient mlRetrainingClient;

    @BeforeEach
    void setUp() {
        List<AuditLog> oldLogs = auditLogRepository.findAll().stream()
                .filter(l -> "MODEL_RECONCILED".equals(l.getAction()))
                .toList();
        if (!oldLogs.isEmpty()) {
            auditLogRepository.deleteAll(oldLogs);
            auditLogRepository.flush();
        }
    }

    @AfterEach
    void tearDown() {
        List<ModelVersion> all = modelVersionRepository.findAll();
        for (ModelVersion mv : all) {
            mv.setIsActive("xgboost-v1".equals(mv.getVersion()));
        }
        modelVersionRepository.saveAllAndFlush(all);

        List<AuditLog> oldLogs = auditLogRepository.findAll().stream()
                .filter(l -> "MODEL_RECONCILED".equals(l.getAction()))
                .toList();
        if (!oldLogs.isEmpty()) {
            auditLogRepository.deleteAll(oldLogs);
            auditLogRepository.flush();
        }
    }

    @Test
    @DisplayName("Startup reconciliation: mismatch triggers activate plus audit row")
    void testMismatchTriggersActivatePlusAuditRow() {
        ModelVersion v2 = modelVersionRepository.findByVersion("xgboost-v2").orElseGet(() -> {
            ModelVersion mv = new ModelVersion();
            mv.setVersion("xgboost-v2");
            mv.setModelName("production-xgboost");
            mv.setAlgorithm("xgboost");
            mv.setModelPath("model_store/xgboost-v2");
            mv.setIsActive(true);
            mv.setMetrics(Map.of("pr_auc", 0.86));
            mv.setCreatedAt(OffsetDateTime.now());
            return modelVersionRepository.saveAndFlush(mv);
        });

        List<ModelVersion> others = modelVersionRepository.findAll().stream()
                .filter(mv -> !"xgboost-v2".equals(mv.getVersion()))
                .toList();
        for (ModelVersion m : others) {
            m.setIsActive(false);
        }
        modelVersionRepository.saveAllAndFlush(others);
        v2.setIsActive(true);
        modelVersionRepository.saveAndFlush(v2);

        when(mlRetrainingClient.getModelInfo()).thenReturn(Map.of("model_version", "xgboost-v1"));

        boolean result = reconciler.reconcile();
        assertTrue(result, "Reconciliation should return true when mismatch is reconciled");

        verify(mlRetrainingClient, times(1)).activate("xgboost-v2");

        List<AuditLog> auditLogs = auditLogRepository.findAll().stream()
                .filter(l -> "MODEL_RECONCILED".equals(l.getAction()))
                .toList();
        assertEquals(1, auditLogs.size(), "Exactly one MODEL_RECONCILED audit row should be written");
        AuditLog audit = auditLogs.get(0);
        assertEquals("MODEL_RECONCILED", audit.getAction());
        assertEquals("xgboost-v2", audit.getEntityId());
        assertNotNull(audit.getDetails());
        assertEquals("xgboost-v2", audit.getDetails().get("dbActive"));
        assertEquals("xgboost-v1", audit.getDetails().get("fastApiWas"));
    }

    @Test
    @DisplayName("Startup reconciliation: match triggers nothing")
    void testMatchTriggersNothing() {
        ModelVersion v1 = modelVersionRepository.findByVersion("xgboost-v1").orElseGet(() -> {
            ModelVersion mv = new ModelVersion();
            mv.setVersion("xgboost-v1");
            mv.setModelName("production-xgboost");
            mv.setAlgorithm("xgboost");
            mv.setModelPath("model_store/xgboost_v1");
            mv.setIsActive(true);
            mv.setMetrics(Map.of("pr_auc", 0.84803));
            mv.setCreatedAt(OffsetDateTime.now());
            return modelVersionRepository.saveAndFlush(mv);
        });
        v1.setIsActive(true);
        modelVersionRepository.saveAndFlush(v1);

        when(mlRetrainingClient.getModelInfo()).thenReturn(Map.of("model_version", "xgboost-v1"));

        boolean result = reconciler.reconcile();
        assertFalse(result, "Reconciliation should return false when versions match");

        verify(mlRetrainingClient, never()).activate(anyString());

        List<AuditLog> auditLogs = auditLogRepository.findAll().stream()
                .filter(l -> "MODEL_RECONCILED".equals(l.getAction()))
                .toList();
        assertTrue(auditLogs.isEmpty(), "No MODEL_RECONCILED audit row should be written on match");
    }

    @Test
    @DisplayName("Startup reconciliation: unreachable FastAPI does not throw")
    void testUnreachableFastApiDoesNotThrow() {
        when(mlRetrainingClient.getModelInfo()).thenThrow(new RestClientException("FastAPI service unreachable"));

        assertDoesNotThrow(() -> {
            boolean result = reconciler.reconcile();
            assertFalse(result, "Reconciliation should return false when FastAPI is unreachable");
        });

        verify(mlRetrainingClient, never()).activate(anyString());

        List<AuditLog> auditLogs = auditLogRepository.findAll().stream()
                .filter(l -> "MODEL_RECONCILED".equals(l.getAction()))
                .toList();
        assertTrue(auditLogs.isEmpty(), "No MODEL_RECONCILED audit row should be written when unreachable");
    }
}
