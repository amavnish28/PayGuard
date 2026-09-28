package com.payguard.transaction.rule;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;

import java.math.BigDecimal;

public class HighAmountRule implements FraudRule {

    public static final String NAME = "HIGH_AMOUNT";
    public static final int SCORE = 25;
    public static final String REASON = "Transaction amount is at least 3x the account historical average";
    private static final BigDecimal THRESHOLD = new BigDecimal("3.0");

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getScore() {
        return SCORE;
    }

    @Override
    public String getReason() {
        return REASON;
    }

    @Override
    public boolean evaluate(Transaction transaction, BehavioralFeatureResult features) {
        if (features == null || features.getAccountAvgAmount() == null || features.getAmountRatio() == null) {
            return false;
        }
        return features.getAccountAvgAmount().compareTo(BigDecimal.ZERO) > 0
                && features.getAmountRatio().compareTo(THRESHOLD) >= 0;
    }
}
