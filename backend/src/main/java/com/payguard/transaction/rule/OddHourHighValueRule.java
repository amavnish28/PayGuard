package com.payguard.transaction.rule;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;

import java.math.BigDecimal;

public class OddHourHighValueRule implements FraudRule {

    public static final String NAME = "ODD_HOUR_HIGH_VALUE";
    public static final int SCORE = 15;
    public static final String REASON = "High-value transaction occurred during an odd hour";
    private static final BigDecimal THRESHOLD = new BigDecimal("2.0");

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
        return Boolean.TRUE.equals(features.getOddHour())
                && features.getAccountAvgAmount().compareTo(BigDecimal.ZERO) > 0
                && features.getAmountRatio().compareTo(THRESHOLD) >= 0;
    }
}
