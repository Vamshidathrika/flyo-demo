package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DelayForecastResponseDto {

    @JsonProperty("flight_number")
    private String flightNumber;

    @JsonProperty("operational_horizon")
    private String operationalHorizon;

    @JsonProperty("expected_delay_minutes")
    private Double expectedDelayMinutes;

    @JsonProperty("delay_p10_minutes")
    private Double delayP10Minutes;

    @JsonProperty("delay_p90_minutes")
    private Double delayP90Minutes;

    @JsonProperty("prediction_interval_width")
    private Double predictionIntervalWidth;

    @JsonProperty("delay_risk_tier")
    private String delayRiskTier;

    @JsonProperty("horizon_explanation")
    private String horizonExplanation;

    @JsonProperty("model_version")
    private String modelVersion;

    @JsonProperty("latency_ms")
    private Double latencyMs;

    public DelayForecastResponseDto() {}

    public static DelayForecastResponseDto fallback(String flightNumber, String horizon) {
        DelayForecastResponseDto dto = new DelayForecastResponseDto();
        dto.setFlightNumber(flightNumber);
        dto.setOperationalHorizon(horizon != null ? horizon : "T_MINUS_2H");
        dto.setExpectedDelayMinutes(0.0);
        dto.setDelayP10Minutes(0.0);
        dto.setDelayP90Minutes(0.0);
        dto.setPredictionIntervalWidth(0.0);
        dto.setDelayRiskTier("UNKNOWN");
        dto.setHorizonExplanation("Prediction service unavailable. Fallback to operational defaults.");
        dto.setModelVersion("unavailable");
        dto.setLatencyMs(0.0);
        return dto;
    }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }

    public String getOperationalHorizon() { return operationalHorizon; }
    public void setOperationalHorizon(String operationalHorizon) { this.operationalHorizon = operationalHorizon; }

    public Double getExpectedDelayMinutes() { return expectedDelayMinutes; }
    public void setExpectedDelayMinutes(Double expectedDelayMinutes) { this.expectedDelayMinutes = expectedDelayMinutes; }

    public Double getDelayP10Minutes() { return delayP10Minutes; }
    public void setDelayP10Minutes(Double delayP10Minutes) { this.delayP10Minutes = delayP10Minutes; }

    public Double getDelayP90Minutes() { return delayP90Minutes; }
    public void setDelayP90Minutes(Double delayP90Minutes) { this.delayP90Minutes = delayP90Minutes; }

    public Double getPredictionIntervalWidth() { return predictionIntervalWidth; }
    public void setPredictionIntervalWidth(Double predictionIntervalWidth) { this.predictionIntervalWidth = predictionIntervalWidth; }

    public String getDelayRiskTier() { return delayRiskTier; }
    public void setDelayRiskTier(String delayRiskTier) { this.delayRiskTier = delayRiskTier; }

    public String getHorizonExplanation() { return horizonExplanation; }
    public void setHorizonExplanation(String horizonExplanation) { this.horizonExplanation = horizonExplanation; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public Double getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Double latencyMs) { this.latencyMs = latencyMs; }
}
