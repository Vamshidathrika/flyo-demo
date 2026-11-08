package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ShapFactorDto {

    @JsonProperty("feature_name")
    private String featureName;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("feature_value")
    private Double featureValue;

    @JsonProperty("shap_value")
    private Double shapValue;

    @JsonProperty("impact_direction")
    private String impactDirection;

    @JsonProperty("absolute_magnitude")
    private Double absoluteMagnitude;

    public ShapFactorDto() {}

    public ShapFactorDto(String featureName, String displayName, Double featureValue,
                         Double shapValue, String impactDirection, Double absoluteMagnitude) {
        this.featureName = featureName;
        this.displayName = displayName;
        this.featureValue = featureValue;
        this.shapValue = shapValue;
        this.impactDirection = impactDirection;
        this.absoluteMagnitude = absoluteMagnitude;
    }

    public String getFeatureName() { return featureName; }
    public void setFeatureName(String featureName) { this.featureName = featureName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Double getFeatureValue() { return featureValue; }
    public void setFeatureValue(Double featureValue) { this.featureValue = featureValue; }

    public Double getShapValue() { return shapValue; }
    public void setShapValue(Double shapValue) { this.shapValue = shapValue; }

    public String getImpactDirection() { return impactDirection; }
    public void setImpactDirection(String impactDirection) { this.impactDirection = impactDirection; }

    public Double getAbsoluteMagnitude() { return absoluteMagnitude; }
    public void setAbsoluteMagnitude(Double absoluteMagnitude) { this.absoluteMagnitude = absoluteMagnitude; }
}
