package com.payguard.retraining.dto;

public class EligibilityResult {

    private final boolean eligible;
    private final String reason;

    public EligibilityResult(boolean eligible, String reason) {
        this.eligible = eligible;
        this.reason = reason;
    }

    public static EligibilityResult eligible(String reason) {
        return new EligibilityResult(true, reason);
    }

    public static EligibilityResult ineligible(String reason) {
        return new EligibilityResult(false, reason);
    }

    public boolean isEligible() {
        return eligible;
    }

    public String getReason() {
        return reason;
    }
}
