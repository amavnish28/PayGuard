package com.payguard.retraining;

import com.payguard.alert.Alert;
import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import com.payguard.ml.MLFeatureVectorMapper;
import com.payguard.ml.MLPredictionRequest;
import com.payguard.retraining.dto.ActivationGateResult;
import com.payguard.retraining.dto.AssembledTrainingData;
import com.payguard.retraining.dto.EligibilityResult;
import com.payguard.retraining.dto.RetrainAdminResponse;
import com.payguard.retraining.dto.RetrainResponseDto;
import com.payguard.retraining.dto.TrainingDataSummary;
import com.payguard.retraining.dto.TrainingExampleDto;
import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import com.payguard.transaction.feature.TransactionFeature;
import com.payguard.verdict.AnalystVerdict;
import com.payguard.verdict.AnalystVerdictRepository;
import com.payguard.verdict.VerdictType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RetrainingService {

    private static final Logger log = LoggerFactory.getLogger(RetrainingService.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("^xgboost-v(\\d+)$");

    private final AnalystVerdictRepository analystVerdictRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final MLRetrainingClient mlRetrainingClient;
    private final RetrainingProperties retrainingProperties;
    private final AuditLogRepository auditLogRepository;
    private final PlatformTransactionManager transactionManager;

    public RetrainingService(AnalystVerdictRepository analystVerdictRepository,
                             ModelVersionRepository modelVersionRepository,
                             MLRetrainingClient mlRetrainingClient,
                             RetrainingProperties retrainingProperties,
                             AuditLogRepository auditLogRepository) {
        this(analystVerdictRepository, modelVersionRepository, mlRetrainingClient, retrainingProperties, auditLogRepository, null);
    }

    @Autowired
    public RetrainingService(AnalystVerdictRepository analystVerdictRepository,
                             ModelVersionRepository modelVersionRepository,
                             MLRetrainingClient mlRetrainingClient,
                             RetrainingProperties retrainingProperties,
                             AuditLogRepository auditLogRepository,
                             @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.analystVerdictRepository = analystVerdictRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.mlRetrainingClient = mlRetrainingClient;
        this.retrainingProperties = retrainingProperties;
        this.auditLogRepository = auditLogRepository;
        this.transactionManager = transactionManager;
    }

    private <T> T runInTransaction(java.util.function.Supplier<T> action) {
        if (transactionManager != null) {
            return new TransactionTemplate(transactionManager).execute(status -> action.get());
        } else {
            return action.get();
        }
    }

    private void runInTransaction(Runnable action) {
        if (transactionManager != null) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    /**
     * Assemble training data from all analyst verdicts joined to alerts, transactions, and transaction_features.
     * Reconstructs 11-feature contract vector using the existing MLFeatureVectorMapper.
     */
    @Transactional(readOnly = true)
    public AssembledTrainingData assembleTrainingData() {
        List<Object[]> records = analystVerdictRepository.findAllVerdictedRecords();

        List<TrainingExampleDto> examples = new ArrayList<>(records.size());
        long fraudCount = 0;
        long legitimateCount = 0;

        for (Object[] row : records) {
            AnalystVerdict verdict = (AnalystVerdict) row[0];
            // Alert alert = (Alert) row[1];
            Transaction transaction = (Transaction) row[2];
            TransactionFeature tf = (TransactionFeature) row[3];

            boolean hasPrevious = tf.getTimeSincePreviousTransactionSeconds() != null;
            BehavioralFeatureResult featureResult = new BehavioralFeatureResult(
                    tf.getTransactionsLast2Min(),
                    tf.getTransactionsLast1Hour(),
                    tf.getAccountAvgAmount(),
                    tf.getAmountRatio(),
                    tf.getTimeSincePreviousTransactionSeconds(),
                    tf.getNewDevice(),
                    tf.getNewLocation(),
                    tf.getTransactionHour(),
                    tf.getOddHour(),
                    hasPrevious
            );

            // Re-use existing MLFeatureVectorMapper directly
            MLPredictionRequest mlReq = MLFeatureVectorMapper.toPredictionRequest(transaction, featureResult);

            Map<String, Object> featureMap = new LinkedHashMap<>();
            featureMap.put("amount", mlReq.getAmount());
            featureMap.put("transactions_last_2_min", mlReq.getTransactionsLast2Min());
            featureMap.put("transactions_last_1_hour", mlReq.getTransactionsLast1Hour());
            featureMap.put("account_avg_amount", mlReq.getAccountAvgAmount());
            featureMap.put("amount_ratio", mlReq.getAmountRatio());
            featureMap.put("time_since_previous_seconds", mlReq.getTimeSincePreviousSeconds());
            featureMap.put("is_first_transaction", mlReq.getIsFirstTransaction());
            featureMap.put("new_device", mlReq.getNewDevice());
            featureMap.put("new_location", mlReq.getNewLocation());
            featureMap.put("transaction_hour", mlReq.getTransactionHour());
            featureMap.put("odd_hour", mlReq.getOddHour());

            int label = (verdict.getVerdict() == VerdictType.FRAUD) ? 1 : 0;
            if (label == 1) {
                fraudCount++;
            } else {
                legitimateCount++;
            }

            examples.add(new TrainingExampleDto(featureMap, label));
        }

        TrainingDataSummary summary = new TrainingDataSummary(records.size(), fraudCount, legitimateCount);
        return new AssembledTrainingData(examples, summary);
    }

    /**
     * Check retraining eligibility against locked gating policy constants.
     */
    public EligibilityResult checkEligibility(TrainingDataSummary summary) {
        int minTotal = retrainingProperties.getMinTotalVerdicts();
        int minPerClass = retrainingProperties.getMinPerClass();

        if (summary.getTotalVerdicts() < minTotal) {
            String reason = String.format(
                    "Ineligible for retraining: total verdicts (%d) is below required minimum of %d (payguard.retraining.min-total-verdicts)",
                    summary.getTotalVerdicts(), minTotal);
            return EligibilityResult.ineligible(reason);
        }

        if (summary.getFraudCount() < minPerClass) {
            String reason = String.format(
                    "Ineligible for retraining: fraud verdicts (%d) is below required minimum per class of %d (payguard.retraining.min-per-class)",
                    summary.getFraudCount(), minPerClass);
            return EligibilityResult.ineligible(reason);
        }

        if (summary.getLegitimateCount() < minPerClass) {
            String reason = String.format(
                    "Ineligible for retraining: legitimate verdicts (%d) is below required minimum per class of %d (payguard.retraining.min-per-class)",
                    summary.getLegitimateCount(), minPerClass);
            return EligibilityResult.ineligible(reason);
        }

        String reason = String.format(
                "Eligible for retraining: %d verdicts (%d fraud, %d legitimate) satisfy minimum criteria (payguard.retraining.min-total-verdicts=%d, payguard.retraining.min-per-class=%d)",
                summary.getTotalVerdicts(), summary.getFraudCount(), summary.getLegitimateCount(), minTotal, minPerClass);
        return EligibilityResult.eligible(reason);
    }

    /**
     * Generate new candidate version name (e.g. xgboost-v2).
     */
    public String generateCandidateVersionName() {
        List<ModelVersion> all = modelVersionRepository.findAll();
        int maxVersion = 1;

        for (ModelVersion mv : all) {
            if (mv.getVersion() != null) {
                Matcher m = VERSION_PATTERN.matcher(mv.getVersion());
                if (m.matches()) {
                    try {
                        int num = Integer.parseInt(m.group(1));
                        if (num > maxVersion) {
                            maxVersion = num;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return "xgboost-v" + (maxVersion + 1);
    }

    /**
     * Trigger candidate retraining on ml-service compute endpoint.
     */
    public RetrainResponseDto triggerRetraining(String candidateVersion, List<TrainingExampleDto> examples) {
        return mlRetrainingClient.retrain(candidateVersion, examples);
    }

    /**
     * Evaluate candidate PR-AUC against the active model's PR-AUC with tolerance.
     */
    public ActivationGateResult evaluateActivationGate(Map<String, Object> candidateMetrics,
                                                        Map<String, Object> activeModelMetrics) {
        double candidatePrAuc = extractPrAuc(candidateMetrics);
        double activePrAuc = extractPrAuc(activeModelMetrics);
        double tolerance = retrainingProperties.getMinPrAucImprovementTolerance();
        double thresholdRequired = activePrAuc + tolerance;

        boolean passed = candidatePrAuc >= thresholdRequired;

        String reason = passed
                ? String.format("Activation gate passed: candidate PR-AUC (%.5f) >= active PR-AUC (%.5f) + tolerance (%.2f) [threshold: %.5f]",
                candidatePrAuc, activePrAuc, tolerance, thresholdRequired)
                : String.format("Activation gate failed: candidate PR-AUC (%.5f) < active PR-AUC (%.5f) + tolerance (%.2f) [threshold: %.5f]",
                candidatePrAuc, activePrAuc, tolerance, thresholdRequired);

        return new ActivationGateResult(passed, candidatePrAuc, activePrAuc, tolerance, thresholdRequired, reason);
    }

    /**
     * Extract PR-AUC from a metrics map.
     */
    public double extractPrAuc(Map<String, Object> metrics) {
        if (metrics == null) {
            return 0.0;
        }
        if (metrics.containsKey("test_pr_auc") && metrics.get("test_pr_auc") instanceof Number n) {
            return n.doubleValue();
        }
        if (metrics.containsKey("test_metrics") && metrics.get("test_metrics") instanceof Map<?, ?> m) {
            if (m.containsKey("pr_auc") && m.get("pr_auc") instanceof Number n) {
                return n.doubleValue();
            }
        }
        if (metrics.containsKey("pr_auc") && metrics.get("pr_auc") instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    /**
     * Execute full retrain, gate evaluation, and conditional activation flow.
     */
    public RetrainAdminResponse executeRetrainWorkflow(UUID adminId) {
        // 1. Assemble training data
        AssembledTrainingData assembledData = assembleTrainingData();
        TrainingDataSummary summary = assembledData.getSummary();

        Map<String, Object> summaryMap = new LinkedHashMap<>();
        summaryMap.put("totalVerdicts", summary.getTotalVerdicts());
        summaryMap.put("fraudCount", summary.getFraudCount());
        summaryMap.put("legitimateCount", summary.getLegitimateCount());

        String activeBefore = modelVersionRepository.findFirstByIsActiveTrueOrderByCreatedAtDesc()
                .map(ModelVersion::getVersion)
                .orElse("xgboost-v1");

        // 2. Check eligibility gate
        EligibilityResult eligibility = checkEligibility(summary);
        if (!eligibility.isEligible()) {
            log.warn("Retraining blocked by eligibility gate: {}", eligibility.getReason());

            // Write audit log entry for blocked attempt
            recordRetrainAttemptAuditLog(
                    adminId,
                    activeBefore,
                    false,
                    summary,
                    null,
                    null,
                    activeBefore,
                    activeBefore,
                    eligibility.getReason()
            );

            throw new RetrainingIneligibleException(eligibility.getReason());
        }

        // 3. Trigger candidate retraining on ml-service
        String candidateVersion = generateCandidateVersionName();
        log.info("Triggering retraining for candidate version '{}'...", candidateVersion);
        RetrainResponseDto retrainResult = triggerRetraining(candidateVersion, assembledData.getExamples());
        Map<String, Object> candidateMetrics = retrainResult.getMetrics();

        // 4. Evaluate activation gate
        Map<String, Object> activeMetrics = modelVersionRepository.findFirstByIsActiveTrueOrderByCreatedAtDesc()
                .map(ModelVersion::getMetrics)
                .orElse(Map.of("pr_auc", 0.84803));

        ActivationGateResult gateResult = evaluateActivationGate(candidateMetrics, activeMetrics);
        boolean gatePassed = gateResult.isPassed();

        String activeAfter;
        boolean activated;

        if (gatePassed) {
            log.info("Activation gate passed: {}. Activating candidate '{}'...", gateResult.getReason(), candidateVersion);

            // Optional candidate file pre-validation ahead of DB commit
            try {
                mlRetrainingClient.validateCandidate(candidateVersion);
            } catch (Exception ex) {
                log.warn("Candidate pre-validation warning: {}", ex.getMessage());
            }

            // DB transaction: deactivate previous active model and insert new candidate as active
            recordActiveCandidateInDb(candidateVersion, candidateMetrics, retrainResult.getModelStorePath());

            // Hot-swap in ml-service
            try {
                mlRetrainingClient.activate(candidateVersion);
                activeAfter = candidateVersion;
                activated = true;
            } catch (Exception ex) {
                log.error("CRITICAL: Mismatch window encountered! Candidate '{}' was set active in DB, but FastAPI activation call failed: {}",
                        candidateVersion, ex.getMessage());

                // Record CRITICAL audit log entry describing the mismatch
                recordCriticalMismatchAuditLog(adminId, candidateVersion, activeBefore, ex.getMessage());
                throw new ModelActivationException("FastAPI activation failed after database update: " + ex.getMessage(), ex);
            }
        } else {
            log.warn("Activation gate failed: {}. Candidate '{}' will be recorded as inactive.", gateResult.getReason(), candidateVersion);
            recordInactiveCandidateInDb(candidateVersion, candidateMetrics, retrainResult.getModelStorePath());
            activeAfter = activeBefore;
            activated = false;
        }

        // 5. Write ONE audit_logs row for the completed attempt
        recordRetrainAttemptAuditLog(
                adminId,
                candidateVersion,
                true,
                summary,
                candidateMetrics,
                gatePassed,
                activeBefore,
                activeAfter,
                gateResult.getReason()
        );

        return new RetrainAdminResponse(
                true,
                candidateVersion,
                candidateMetrics,
                activated,
                gateResult.getReason(),
                summaryMap
        );
    }

    public String normalizeModelPath(String version, String modelPath) {
        if (modelPath == null || modelPath.isBlank()) {
            return "model_store/" + version;
        }
        String normalized = modelPath.replace('\\', '/').trim();
        int idx = normalized.indexOf("model_store/");
        if (idx >= 0) {
            return normalized.substring(idx);
        }
        return "model_store/" + version;
    }

    @Transactional
    public ModelVersion recordActiveCandidateInDb(String version, Map<String, Object> metrics, String modelPath) {
        return runInTransaction(() -> {
            List<ModelVersion> activeModels = modelVersionRepository.findAllByIsActiveTrue();
            for (ModelVersion m : activeModels) {
                m.setIsActive(false);
            }
            if (!activeModels.isEmpty()) {
                modelVersionRepository.saveAll(activeModels);
            }

            ModelVersion mv = new ModelVersion();
            mv.setVersion(version);
            mv.setModelName("production-xgboost");
            mv.setAlgorithm("xgboost");
            mv.setMetrics(metrics);
            mv.setModelPath(normalizeModelPath(version, modelPath));
            mv.setIsActive(true);
            mv.setCreatedAt(OffsetDateTime.now());
            return modelVersionRepository.saveAndFlush(mv);
        });
    }

    @Transactional
    public ModelVersion recordInactiveCandidateInDb(String version, Map<String, Object> metrics, String modelPath) {
        return runInTransaction(() -> {
            ModelVersion mv = new ModelVersion();
            mv.setVersion(version);
            mv.setModelName("production-xgboost");
            mv.setAlgorithm("xgboost");
            mv.setMetrics(metrics);
            mv.setModelPath(normalizeModelPath(version, modelPath));
            mv.setIsActive(false);
            mv.setCreatedAt(OffsetDateTime.now());
            return modelVersionRepository.saveAndFlush(mv);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCriticalMismatchAuditLog(UUID userId, String candidateVersion, String activeBefore, String errorMsg) {
        runInTransaction(() -> {
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId);
            auditLog.setAction("MODEL_ACTIVATION_MISMATCH_CRITICAL");
            auditLog.setEntityType("model_version");
            auditLog.setEntityId(candidateVersion);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("error", errorMsg);
            details.put("candidateVersion", candidateVersion);
            details.put("activeBefore", activeBefore);
            details.put("status", "DB_ACTIVE_FASTAPI_FAILED");
            details.put("reconciliationCommand", "SELECT version, is_active FROM model_versions WHERE is_active = true;");
            auditLog.setDetails(details);
            auditLog.setCreatedAt(OffsetDateTime.now());

            auditLogRepository.saveAndFlush(auditLog);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRetrainAttemptAuditLog(
            UUID userId,
            String candidateVersion,
            boolean eligible,
            TrainingDataSummary dataSummary,
            Map<String, Object> candidateMetrics,
            Boolean activationGatePassed,
            String activeBefore,
            String activeAfter,
            String reason) {
        runInTransaction(() -> {
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId);
            auditLog.setAction("MODEL_RETRAIN_ATTEMPTED");
            auditLog.setEntityType("model_version");
            auditLog.setEntityId(candidateVersion != null ? candidateVersion : activeBefore);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("eligible", eligible);

            if (dataSummary != null) {
                Map<String, Object> summaryMap = new LinkedHashMap<>();
                summaryMap.put("totalVerdicts", dataSummary.getTotalVerdicts());
                summaryMap.put("fraudCount", dataSummary.getFraudCount());
                summaryMap.put("legitimateCount", dataSummary.getLegitimateCount());
                details.put("trainingDataSummary", summaryMap);
            } else {
                details.put("trainingDataSummary", Map.of());
            }

            if (candidateMetrics != null) {
                details.put("candidateMetrics", candidateMetrics);
            }
            if (activationGatePassed != null) {
                details.put("activationGatePassed", activationGatePassed);
            }
            details.put("activeBefore", activeBefore);
            details.put("activeAfter", activeAfter);
            details.put("reason", reason);

            auditLog.setDetails(details);
            auditLog.setCreatedAt(OffsetDateTime.now());

            auditLogRepository.saveAndFlush(auditLog);
        });
    }
}
