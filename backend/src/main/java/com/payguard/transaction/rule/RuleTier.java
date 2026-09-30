package com.payguard.transaction.rule;

public enum RuleTier {
    LOW,
    MEDIUM,
    HIGH;

    public static RuleTier fromScore(int ruleScore) {
        if (ruleScore < 30) {
            return LOW;
        } else if (ruleScore < 45) {
            return MEDIUM;
        } else {
            return HIGH;
        }
    }
}
