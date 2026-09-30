package com.payguard.ml;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ShapReason {

    @JsonProperty("feature")
    private String feature;

    @JsonProperty("shap_value")
    private Double shapValue;

    @JsonProperty("feature_value")
    private Object featureValue;

    public ShapReason() {
    }

    public ShapReason(String feature, Double shapValue, Object featureValue) {
        this.feature = feature;
        this.shapValue = shapValue;
        this.featureValue = featureValue;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public Double getShapValue() {
        return shapValue;
    }

    public void setShapValue(Double shapValue) {
        this.shapValue = shapValue;
    }

    public Object getFeatureValue() {
        return featureValue;
    }

    public void setFeatureValue(Object featureValue) {
        this.featureValue = featureValue;
    }
}
