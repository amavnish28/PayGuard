package com.payguard.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "payguard.ml-service.base-url=http://127.0.0.1:1",
        "payguard.ml-service.timeout-ms=250"
})
class MLServiceClientCircuitBreakerIntegrationTest {

    @Autowired
    private MLServiceClient mlServiceClient;

    @Test
    @DisplayName("Circuit breaker test: point MLServiceClient at unreachable port (http://127.0.0.1:1), confirm predict() returns Optional.empty() within timeout and does not throw")
    void testPredictWithUnreachableServiceReturnsEmptyWithoutThrowing() {
        MLPredictionRequest request = new MLPredictionRequest(
                50.0, 0, 0, 50.0, 1.0, 100.0, 0, 0, 0, 12, 0
        );

        long start = System.currentTimeMillis();
        Optional<MLPredictionResponse> responseOpt = assertDoesNotThrow(() -> mlServiceClient.predict(request));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(responseOpt.isEmpty(), "predict() must return empty Optional when ML service is unreachable");
        assertTrue(elapsed < 3000, "HTTP call must fail fast within timeout/connection limit (elapsed: " + elapsed + "ms)");
        assertEquals("connection_error", mlServiceClient.getLastFailureReason());
    }
}
