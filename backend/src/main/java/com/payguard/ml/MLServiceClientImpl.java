package com.payguard.ml;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Optional;

@Service
public class MLServiceClientImpl implements MLServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MLServiceClientImpl.class);

    private final RestClient restClient;
    private final ThreadLocal<String> lastFailureReason = new ThreadLocal<>();

    public MLServiceClientImpl(@Value("${payguard.ml-service.base-url:http://localhost:8001}") String baseUrl,
                               @Value("${payguard.ml-service.timeout-ms:150}") int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @CircuitBreaker(name = "mlService", fallbackMethod = "predictFallback")
    public Optional<MLPredictionResponse> predict(MLPredictionRequest request) {
        lastFailureReason.remove();
        MLPredictionResponse response = restClient.post()
                .uri("/api/v1/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MLPredictionResponse.class);

        return Optional.ofNullable(response);
    }

    public Optional<MLPredictionResponse> predictFallback(MLPredictionRequest request, Throwable throwable) {
        String reason = determineFailureReason(throwable);
        lastFailureReason.set(reason);

        log.warn("ML service call failed ({}) for predict request: {}", reason, throwable.getMessage());
        return Optional.empty();
    }

    @Override
    public String getLastFailureReason() {
        String reason = lastFailureReason.get();
        return reason != null ? reason : "unknown";
    }

    private String determineFailureReason(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = t.getMessage() != null ? t.getMessage() : "";
        String rootMsg = root.getMessage() != null ? root.getMessage() : "";

        if (root instanceof SocketTimeoutException
                || msg.contains("timed out")
                || rootMsg.contains("timed out")
                || msg.contains("Timeout")
                || rootMsg.contains("Timeout")) {
            return "timeout";
        }
        if (root instanceof ConnectException
                || msg.contains("Connection refused")
                || rootMsg.contains("Connection refused")
                || msg.contains("Failed to connect")
                || rootMsg.contains("Failed to connect")) {
            return "connection_error";
        }
        if (t instanceof HttpServerErrorException
                || (t instanceof RestClientResponseException rre && rre.getStatusCode().is5xxServerError())
                || msg.contains("500")
                || msg.contains("502")
                || msg.contains("503")
                || msg.contains("504")) {
            return "5xx";
        }
        return "connection_error";
    }
}
