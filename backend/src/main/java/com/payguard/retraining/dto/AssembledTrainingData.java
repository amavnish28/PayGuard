package com.payguard.retraining.dto;

import java.util.List;

public class AssembledTrainingData {

    private final List<TrainingExampleDto> examples;
    private final TrainingDataSummary summary;

    public AssembledTrainingData(List<TrainingExampleDto> examples, TrainingDataSummary summary) {
        this.examples = examples;
        this.summary = summary;
    }

    public List<TrainingExampleDto> getExamples() {
        return examples;
    }

    public TrainingDataSummary getSummary() {
        return summary;
    }
}
