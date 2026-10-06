package com.payguard.retraining;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Locked gating policy configuration properties for model retraining and activation.
 *
 * All three gating constants are declared and referenced by name throughout the service,
 * audit logging, and response reporting.
 */
@Component
@ConfigurationProperties(prefix = "payguard.retraining")
public class RetrainingProperties {

    public static final int DEFAULT_MIN_TOTAL_VERDICTS = 20;
    public static final int DEFAULT_MIN_PER_CLASS = 5;
    public static final double DEFAULT_MIN_PR_AUC_IMPROVEMENT_TOLERANCE = -0.02;

    /**
     * Minimum total analyst verdicts required to pass the eligibility gate.
     * Property: payguard.retraining.min-total-verdicts (default: 20)
     */
    private int minTotalVerdicts = DEFAULT_MIN_TOTAL_VERDICTS;

    /**
     * Minimum verdicts required per class (at least 5 FRAUD and at least 5 LEGITIMATE).
     * Property: payguard.retraining.min-per-class (default: 5)
     */
    private int minPerClass = DEFAULT_MIN_PER_CLASS;

    /**
     * Gating tolerance for PR-AUC improvement comparison.
     * Property: payguard.retraining.min-pr-auc-improvement-tolerance (default: -0.02)
     *
     * SIGN CONVENTION:
     * A candidate model is acceptable if:
     * candidate_pr_auc >= active_pr_auc + minPrAucImprovementTolerance
     *
     * With a negative tolerance of -0.02, adding tolerance to the active PR-AUC lowers the
     * hurdle: candidate_pr_auc >= (active_pr_auc - 0.02). That is, the candidate may be up
     * to 0.02 WORSE than the active model and still pass the activation gate.
     */
    private double minPrAucImprovementTolerance = DEFAULT_MIN_PR_AUC_IMPROVEMENT_TOLERANCE;

    /**
     * Read and connect timeout in milliseconds for retraining calls to ml-service.
     */
    private int timeoutMs = 120000;

    public RetrainingProperties() {
    }

    public int getMinTotalVerdicts() {
        return minTotalVerdicts;
    }

    public void setMinTotalVerdicts(int minTotalVerdicts) {
        this.minTotalVerdicts = minTotalVerdicts;
    }

    public int getMinPerClass() {
        return minPerClass;
    }

    public void setMinPerClass(int minPerClass) {
        this.minPerClass = minPerClass;
    }

    public double getMinPrAucImprovementTolerance() {
        return minPrAucImprovementTolerance;
    }

    public void setMinPrAucImprovementTolerance(double minPrAucImprovementTolerance) {
        this.minPrAucImprovementTolerance = minPrAucImprovementTolerance;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
