package com.payguard.ml;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;

public final class MLFeatureVectorMapper {

    private MLFeatureVectorMapper() {
    }

    public static MLPredictionRequest toPredictionRequest(Transaction transaction, BehavioralFeatureResult features) {
        Double amount = transaction.getAmount() != null ? transaction.getAmount().doubleValue() : 0.0;
        Integer txns2Min = features.getTransactionsLast2Min() != null ? features.getTransactionsLast2Min() : 0;
        Integer txns1Hour = features.getTransactionsLast1Hour() != null ? features.getTransactionsLast1Hour() : 0;
        Double avgAmount = features.getAccountAvgAmount() != null ? features.getAccountAvgAmount().doubleValue() : 0.0;
        Double ratio = features.getAmountRatio() != null ? features.getAmountRatio().doubleValue() : 0.0;

        // If no previous transaction exists (null), map to -1.0 per schema contract
        Double timeSincePrev = features.getTimeSincePreviousTransactionSeconds() != null
                ? features.getTimeSincePreviousTransactionSeconds().doubleValue()
                : -1.0;

        // is_first_transaction = 1 when account has no previous transactions, else 0
        Integer isFirstTxn = features.hasPreviousTransactions() ? 0 : 1;

        Integer newDev = Boolean.TRUE.equals(features.getNewDevice()) ? 1 : 0;
        Integer newLoc = Boolean.TRUE.equals(features.getNewLocation()) ? 1 : 0;
        Integer hour = features.getTransactionHour() != null ? features.getTransactionHour().intValue() : 0;
        Integer odd = Boolean.TRUE.equals(features.getOddHour()) ? 1 : 0;

        return new MLPredictionRequest(
                amount,
                txns2Min,
                txns1Hour,
                avgAmount,
                ratio,
                timeSincePrev,
                isFirstTxn,
                newDev,
                newLoc,
                hour,
                odd
        );
    }
}
