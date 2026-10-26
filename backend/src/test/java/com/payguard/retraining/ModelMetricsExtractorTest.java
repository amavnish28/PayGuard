package com.payguard.retraining;

import com.payguard.audit.AuditLog;
import com.payguard.retraining.dto.ModelMetricsSummary;
import com.payguard.retraining.dto.RetrainingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelMetricsExtractorTest {

    @Test
    @DisplayName("v1-shaped JSON extracts metrics correctly")
    void testV1ShapedJson() {
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("pr_auc", 0.84803);
        v1.put("roc_auc", 0.98389);
        v1.put("test_pr_auc", 0.84803);
        v1.put("test_roc_auc", 0.98389);
        v1.put("block_threshold", 0.56667);
        v1.put("review_threshold", 0.18657);

        ModelMetricsSummary summary = ModelMetricsExtractor.extractMetrics(v1);

        assertNotNull(summary);
        assertEquals(0.84803, summary.getTestPrAuc(), 1e-5);
        assertEquals(0.98389, summary.getTestRocAuc(), 1e-5);
        assertEquals(0.56667, summary.getBlockThreshold(), 1e-5);
        assertEquals(0.18657, summary.getReviewThreshold(), 1e-5);
        assertNull(summary.getValPrAuc());
        assertNull(summary.getValRocAuc());
        assertNull(summary.getValBrier());
    }

    @Test
    @DisplayName("v2-shaped JSON with nested objects and arrays extracts only target keys")
    void testV2ShapedJsonWithNestedObjectsAndArrays() {
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("pr_auc", 0.8525);
        v2.put("roc_auc", 0.98351);
        v2.put("val_brier", 0.00259);
        v2.put("val_pr_auc", 0.88856);
        v2.put("val_roc_auc", 0.98802);
        v2.put("test_pr_auc", 0.8525);
        v2.put("test_roc_auc", 0.98351);
        v2.put("block_threshold", 0.44068);
        v2.put("review_threshold", 0.38462);

        // Nested objects and arrays that must be ignored
        Map<String, Object> nestedVal = new HashMap<>();
        nestedVal.put("pr_auc", 0.88856);
        nestedVal.put("roc_auc", 0.98802);
        v2.put("val_metrics", nestedVal);

        Map<String, Object> nestedTest = new HashMap<>();
        nestedTest.put("test_rows", 67415);
        nestedTest.put("matrix", Arrays.asList(Arrays.asList(66741, 52), Arrays.asList(161, 461)));
        v2.put("test_metrics", nestedTest);

        v2.put("feature_order", Arrays.asList("amount", "velocity", "new_device"));
        v2.put("unknown_array", Collections.singletonList("item"));

        ModelMetricsSummary summary = ModelMetricsExtractor.extractMetrics(v2);

        assertNotNull(summary);
        assertEquals(0.8525, summary.getTestPrAuc(), 1e-5);
        assertEquals(0.98351, summary.getTestRocAuc(), 1e-5);
        assertEquals(0.88856, summary.getValPrAuc(), 1e-5);
        assertEquals(0.98802, summary.getValRocAuc(), 1e-5);
        assertEquals(0.00259, summary.getValBrier(), 1e-5);
        assertEquals(0.44068, summary.getBlockThreshold(), 1e-5);
        assertEquals(0.38462, summary.getReviewThreshold(), 1e-5);
    }

    @Test
    @DisplayName("null metrics column results in all-null summary object")
    void testNullMetrics() {
        ModelMetricsSummary summary = ModelMetricsExtractor.extractMetrics(null);

        assertNotNull(summary);
        assertNull(summary.getTestPrAuc());
        assertNull(summary.getTestRocAuc());
        assertNull(summary.getValPrAuc());
        assertNull(summary.getValRocAuc());
        assertNull(summary.getValBrier());
        assertNull(summary.getBlockThreshold());
        assertNull(summary.getReviewThreshold());
    }

    @Test
    @DisplayName("string numerics and BigDecimal parse correctly; non-numeric strings become null, never 0")
    void testStringNumerics() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("test_pr_auc", "0.8912");
        map.put("test_roc_auc", BigDecimal.valueOf(0.9915));
        map.put("val_pr_auc", 1); // Integer
        map.put("val_roc_auc", "not_a_number");
        map.put("val_brier", "");
        map.put("block_threshold", "  0.55  ");
        map.put("review_threshold", null);

        ModelMetricsSummary summary = ModelMetricsExtractor.extractMetrics(map);

        assertNotNull(summary);
        assertEquals(0.8912, summary.getTestPrAuc(), 1e-5);
        assertEquals(0.9915, summary.getTestRocAuc(), 1e-5);
        assertEquals(1.0, summary.getValPrAuc(), 1e-5);
        assertNull(summary.getValRocAuc(), "Non-numeric string must be null, never 0");
        assertNull(summary.getValBrier(), "Empty string must be null, never 0");
        assertEquals(0.55, summary.getBlockThreshold(), 1e-5);
        assertNull(summary.getReviewThreshold());
    }

    @Test
    @DisplayName("missing keys map to nulls, never 0")
    void testMissingKeys() {
        Map<String, Object> emptyMap = new HashMap<>();
        ModelMetricsSummary summary = ModelMetricsExtractor.extractMetrics(emptyMap);

        assertNotNull(summary);
        assertNull(summary.getTestPrAuc());
        assertNull(summary.getTestRocAuc());
        assertNull(summary.getValPrAuc());
        assertNull(summary.getValRocAuc());
        assertNull(summary.getValBrier());
        assertNull(summary.getBlockThreshold());
        assertNull(summary.getReviewThreshold());
    }

    @Test
    @DisplayName("an unrelated key is ignored")
    void testUnrelatedKeyIgnored() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("unrelatedKey", "arbitrary");
        map.put("model_path", "/opt/models/model.joblib");
        map.put("block_threshold", 0.60);

        ModelMetricsSummary summary = ModelMetricsExtractor.extractMetrics(map);

        assertNotNull(summary);
        assertEquals(0.60, summary.getBlockThreshold(), 1e-5);
        assertNull(summary.getTestPrAuc());
    }

    @Test
    @DisplayName("testPrAuc and testRocAuc fallback to pr_auc and roc_auc when test_ keys absent")
    void testFallbackKeys() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pr_auc", 0.8123);
        map.put("roc_auc", 0.9456);

        ModelMetricsSummary summary = ModelMetricsExtractor.extractMetrics(map);

        assertNotNull(summary);
        assertEquals(0.8123, summary.getTestPrAuc(), 1e-5);
        assertEquals(0.9456, summary.getTestRocAuc(), 1e-5);
    }

    @Test
    @DisplayName("AuditLog with sparse details maps to nulls without error")
    void testSparseAuditLogDetails() {
        AuditLog log = new AuditLog();
        log.setAction("MODEL_RECONCILED");
        log.setEntityId("xgboost-v1");
        log.setCreatedAt(OffsetDateTime.now());
        log.setDetails(new HashMap<>());

        RetrainingEvent event = ModelMetricsExtractor.extractRetrainingEvent(log);

        assertNotNull(event);
        assertEquals("MODEL_RECONCILED", event.getAction());
        assertEquals("xgboost-v1", event.getEntityId());
        assertNull(event.getEligible());
        assertNull(event.getActivationGatePassed());
        assertNull(event.getReason());
        assertNull(event.getActiveBefore());
        assertNull(event.getActiveAfter());
        assertNull(event.getTotalVerdicts());
        assertNull(event.getFraudCount());
        assertNull(event.getLegitimateCount());
        assertNull(event.getCandidatePrAuc());
    }

    @Test
    @DisplayName("AuditLog with null details maps gracefully")
    void testNullAuditLogDetails() {
        AuditLog log = new AuditLog();
        log.setAction("MODEL_ACTIVATION_MISMATCH_CRITICAL");
        log.setEntityId("xgboost-v2");
        log.setCreatedAt(OffsetDateTime.now());
        log.setDetails(null);

        RetrainingEvent event = ModelMetricsExtractor.extractRetrainingEvent(log);

        assertNotNull(event);
        assertEquals("MODEL_ACTIVATION_MISMATCH_CRITICAL", event.getAction());
        assertNull(event.getEligible());
    }
}
