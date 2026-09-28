package com.payguard.transaction.rule;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;

public class VelocityRule implements FraudRule {

    public static final String NAME = "VELOCITY";
    public static final int SCORE = 30;
    public static final String REASON = "5 or more transactions from the account within 2 minutes";

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
        if (features == null || features.getTransactionsLast2Min() == null) {
            return false;
        }
        return (features.getTransactionsLast2Min() + 1) >= 5;
    }
}
