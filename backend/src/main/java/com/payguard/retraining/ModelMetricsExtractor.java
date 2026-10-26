package com.payguard.retraining;

import com.payguard.audit.AuditLog;
import com.payguard.retraining.dto.ModelMetricsSummary;
import com.payguard.retraining.dto.RetrainingEvent;

import java.util.Map;

public final class ModelMetricsExtractor {

    private ModelMetricsExtractor() {
    }

    /**
     * Extracts only the specified metrics fields into ModelMetricsSummary.
     * Missing or non-numeric values become null, never 0.
     * Null input maps to an all-null object.
     * Ignores nested objects, arrays, and unrelated keys.
     */
    public static ModelMetricsSummary extractMetrics(Map<String, Object> metrics) {
        if (metrics == null) {
            return new ModelMetricsSummary(null, null, null, null, null, null, null);
        }

        Double testPrAuc = toDouble(metrics.get("test_pr_auc"));
        if (testPrAuc == null) {
            testPrAuc = toDouble(metrics.get("pr_auc"));
        }

        Double testRocAuc = toDouble(metrics.get("test_roc_auc"));
        if (testRocAuc == null) {
            testRocAuc = toDouble(metrics.get("roc_auc"));
        }

        Double valPrAuc = toDouble(metrics.get("val_pr_auc"));
        Double valRocAuc = toDouble(metrics.get("val_roc_auc"));
        Double valBrier = toDouble(metrics.get("val_brier"));
        Double blockThreshold = toDouble(metrics.get("block_threshold"));
        Double reviewThreshold = toDouble(metrics.get("review_threshold"));

        return new ModelMetricsSummary(
                testPrAuc,
                testRocAuc,
                valPrAuc,
                valRocAuc,
                valBrier,
                blockThreshold,
                reviewThreshold
        );
    }

    /**
     * Tolerantly maps an AuditLog entry to a RetrainingEvent DTO.
     * Every detail field is nullable and read tolerantly from the details JSON.
     */
    public static RetrainingEvent extractRetrainingEvent(AuditLog log) {
        if (log == null) {
            return null;
        }

        String action = log.getAction();
        String entityId = log.getEntityId();
        java.time.OffsetDateTime createdAt = log.getCreatedAt();
        Map<String, Object> details = log.getDetails();

        if (details == null) {
            return new RetrainingEvent(
                    action, entityId, createdAt,
                    null, null, null, null, null, null, null, null, null
            );
        }

        Boolean eligible = toBoolean(details.get("eligible"));
        Boolean activationGatePassed = toBoolean(details.get("activationGatePassed"));

        String reason = null;
        if (details.get("reason") != null) {
            reason = String.valueOf(details.get("reason"));
        } else if (details.get("error") != null) {
            reason = String.valueOf(details.get("error"));
        }

        String activeBefore = null;
        if (details.get("activeBefore") != null) {
            activeBefore = String.valueOf(details.get("activeBefore"));
        } else if (details.get("fastApiWas") != null) {
            activeBefore = String.valueOf(details.get("fastApiWas"));
        }

        String activeAfter = null;
        if (details.get("activeAfter") != null) {
            activeAfter = String.valueOf(details.get("activeAfter"));
        } else if (details.get("dbActive") != null) {
            activeAfter = String.valueOf(details.get("dbActive"));
        }

        Integer totalVerdicts = null;
        Integer fraudCount = null;
        Integer legitimateCount = null;

        Object tdsObj = details.get("trainingDataSummary");
        if (tdsObj instanceof Map<?, ?> tdsMap) {
            totalVerdicts = toInteger(tdsMap.get("totalVerdicts"));
            fraudCount = toInteger(tdsMap.get("fraudCount"));
            legitimateCount = toInteger(tdsMap.get("legitimateCount"));
        } else {
            totalVerdicts = toInteger(details.get("totalVerdicts"));
            fraudCount = toInteger(details.get("fraudCount"));
            legitimateCount = toInteger(details.get("legitimateCount"));
        }

        Double candidatePrAuc = null;
        Object cmObj = details.get("candidateMetrics");
        if (cmObj instanceof Map<?, ?> cmMap) {
            candidatePrAuc = toDouble(cmMap.get("test_pr_auc"));
            if (candidatePrAuc == null) {
                candidatePrAuc = toDouble(cmMap.get("pr_auc"));
            }
        }
        if (candidatePrAuc == null) {
            candidatePrAuc = toDouble(details.get("candidatePrAuc"));
        }
        if (candidatePrAuc == null) {
            candidatePrAuc = toDouble(details.get("candidate_pr_auc"));
        }

        return new RetrainingEvent(
                action,
                entityId,
                createdAt,
                eligible,
                activationGatePassed,
                reason,
                activeBefore,
                activeAfter,
                totalVerdicts,
                fraudCount,
                legitimateCount,
                candidatePrAuc
        );
    }

    public static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
        }
        return null;
    }
}
