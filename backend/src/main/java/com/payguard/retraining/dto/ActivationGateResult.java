package com.payguard.retraining.dto;

public class ActivationGateResult {

    private final boolean passed;
    private final double candidatePrAuc;
    private final double activePrAuc;
    private final double tolerance;
    private final double thresholdRequired;
    private final String reason;

    public ActivationGateResult(boolean passed, double candidatePrAuc, double activePrAuc,
                                double tolerance, double thresholdRequired, String reason) {
        this.passed = passed;
        this.candidatePrAuc = candidatePrAuc;
        this.activePrAuc = activePrAuc;
        this.tolerance = tolerance;
        this.thresholdRequired = thresholdRequired;
        this.reason = reason;
    }

    public boolean isPassed() {
        return passed;
    }

    public double getCandidatePrAuc() {
        return candidatePrAuc;
    }

    public double getActivePrAuc() {
        return activePrAuc;
    }

    public double getTolerance() {
        return tolerance;
    }

    public double getThresholdRequired() {
        return thresholdRequired;
    }

    public String getReason() {
        return reason;
    }
}
