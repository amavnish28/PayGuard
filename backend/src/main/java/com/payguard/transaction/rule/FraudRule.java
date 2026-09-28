package com.payguard.transaction.rule;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;

public interface FraudRule {

    String getName();

    int getScore();

    String getReason();

    boolean evaluate(Transaction transaction, BehavioralFeatureResult features);
}
