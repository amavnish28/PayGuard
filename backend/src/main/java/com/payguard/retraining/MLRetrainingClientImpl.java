package com.payguard.retraining;

import com.payguard.retraining.dto.RetrainResponseDto;
import com.payguard.retraining.dto.TrainingExampleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MLRetrainingClientImpl implements MLRetrainingClient {

    private static final Logger log = LoggerFactory.getLogger(MLRetrainingClientImpl.class);

    private final RestClient restClient;

    public MLRetrainingClientImpl(
            @Value("${payguard.ml-service.base-url:http://localhost:8001}") String baseUrl,
            RetrainingProperties retrainingProperties) {
        int timeoutMs = retrainingProperties.getTimeoutMs();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RetrainResponseDto retrain(String candidateVersion, List<TrainingExampleDto> examples) {
        log.info("Dispatching retrain request for candidate '{}' with {} examples to ml-service...",
                candidateVersion, examples.size());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_version_candidate", candidateVersion);
        payload.put("training_examples", examples);

        return restClient.post()
                .uri("/api/v1/retrain")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(RetrainResponseDto.class);
    }

    @Override
    public void activate(String modelVersion) {
        log.info("Dispatching model activation request for version '{}' to ml-service...", modelVersion);

        Map<String, String> payload = Map.of("model_version", modelVersion);

        restClient.post()
                .uri("/api/v1/model/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.info("Successfully activated model version '{}' on ml-service.", modelVersion);
    }

    @Override
    public void validateCandidate(String modelVersion) {
        log.info("Dispatching candidate validation request for version '{}' to ml-service...", modelVersion);

        Map<String, String> payload = Map.of("model_version", modelVersion);

        restClient.post()
                .uri("/api/v1/model/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public Map<String, Object> getModelInfo() {
        return restClient.get()
                .uri("/api/v1/model/info")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
