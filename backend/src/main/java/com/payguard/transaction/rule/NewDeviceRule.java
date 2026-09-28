package com.payguard.transaction.rule;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;

public class NewDeviceRule implements FraudRule {

    public static final String NAME = "NEW_DEVICE";
    public static final int SCORE = 15;
    public static final String REASON = "Transaction uses a device not previously seen for this account";

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
        if (features == null) {
            return false;
        }
        return Boolean.TRUE.equals(features.getNewDevice()) && features.hasPreviousTransactions();
    }
}
