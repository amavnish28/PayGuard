package com.payguard.ml;

import java.util.Optional;

public interface MLServiceClient {

    Optional<MLPredictionResponse> predict(MLPredictionRequest request);

    default String getLastFailureReason() {
        return "unknown";
    }
}
