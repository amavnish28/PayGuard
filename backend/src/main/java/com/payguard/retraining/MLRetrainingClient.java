package com.payguard.retraining;

import com.payguard.retraining.dto.RetrainResponseDto;
import com.payguard.retraining.dto.TrainingExampleDto;

import java.util.List;
import java.util.Map;

public interface MLRetrainingClient {

    RetrainResponseDto retrain(String candidateVersion, List<TrainingExampleDto> examples);

    void activate(String modelVersion);

    void validateCandidate(String modelVersion);

    Map<String, Object> getModelInfo();
}
