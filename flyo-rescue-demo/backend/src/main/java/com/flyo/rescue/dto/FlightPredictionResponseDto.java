package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FlightPredictionResponseDto {

    @JsonProperty("prediction_id")
    private String predictionId;

    @JsonProperty("flight_number")
    private String flightNumber;

    @JsonProperty("carrier_code")
    private String carrierCode;

    @JsonProperty("origin_airport")
    private String originAirport;

    @JsonProperty("dest_airport")
    private String destAirport;

    @JsonProperty("status")
    private String status;

    @JsonProperty("status_reason")
    private String statusReason;

    @JsonProperty("disruption_probability")
    private Double disruptionProbability;

    @JsonProperty("cancellation_probability")
    private Double cancellationProbability;

    @JsonProperty("delay_120m_probability")
    private Double delay120mProbability;

    @JsonProperty("expected_delay_minutes")
    private Double expectedDelayMinutes;

    @JsonProperty("delay_p10_minutes")
    private Double delayP10Minutes;

    @JsonProperty("delay_p90_minutes")
    private Double delayP90Minutes;

    @JsonProperty("disruption_risk_level")
    private String disruptionRiskLevel;

    @JsonProperty("risk_factors")
    private List<String> riskFactors = new ArrayList<>();

    @JsonProperty("top_contributing_factors")
    private List<ShapFactorDto> topContributingFactors = new ArrayList<>();

    @JsonProperty("weather_hazards")
    private List<String> weatherHazards = new ArrayList<>();

    @JsonProperty("is_experimental")
    private boolean isExperimental = true;

    @JsonProperty("is_duration_imputed")
    private boolean isDurationImputed = false;

    @JsonProperty("is_distance_imputed")
    private boolean isDistanceImputed = false;

    @JsonProperty("disclaimer")
    private String disclaimer = "Experimental intelligence only. Extreme delay >=120m is a flight delay classification target, never a missed connection probability. Human operator approval is strictly required before rebooking.";

    @JsonProperty("model_version")
    private String modelVersion;

    @JsonProperty("inference_latency_ms")
    private Double inferenceLatencyMs;

    public FlightPredictionResponseDto() {}

    public static FlightPredictionResponseDto fallback(String flightNumber, String carrierCode,
                                                       String origin, String dest, String status, String reason) {
        FlightPredictionResponseDto dto = new FlightPredictionResponseDto();
        dto.setFlightNumber(flightNumber);
        dto.setCarrierCode(carrierCode);
        dto.setOriginAirport(origin);
        dto.setDestAirport(dest);
        dto.setStatus(status);
        dto.setStatusReason(reason);
        dto.setDisruptionRiskLevel("UNKNOWN");
        dto.setExperimental(true);
        dto.setModelVersion("unavailable");
        dto.setInferenceLatencyMs(0.0);
        return dto;
    }

    public String getPredictionId() { return predictionId; }
    public void setPredictionId(String predictionId) { this.predictionId = predictionId; }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getOriginAirport() { return originAirport; }
    public void setOriginAirport(String originAirport) { this.originAirport = originAirport; }

    public String getDestAirport() { return destAirport; }
    public void setDestAirport(String destAirport) { this.destAirport = destAirport; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public Double getDisruptionProbability() { return disruptionProbability; }
    public void setDisruptionProbability(Double disruptionProbability) { this.disruptionProbability = disruptionProbability; }

    public Double getCancellationProbability() { return cancellationProbability; }
    public void setCancellationProbability(Double cancellationProbability) { this.cancellationProbability = cancellationProbability; }

    public Double getDelay120mProbability() { return delay120mProbability; }
    public void setDelay120mProbability(Double delay120mProbability) { this.delay120mProbability = delay120mProbability; }

    public Double getExpectedDelayMinutes() { return expectedDelayMinutes; }
    public void setExpectedDelayMinutes(Double expectedDelayMinutes) { this.expectedDelayMinutes = expectedDelayMinutes; }

    public Double getDelayP10Minutes() { return delayP10Minutes; }
    public void setDelayP10Minutes(Double delayP10Minutes) { this.delayP10Minutes = delayP10Minutes; }

    public Double getDelayP90Minutes() { return delayP90Minutes; }
    public void setDelayP90Minutes(Double delayP90Minutes) { this.delayP90Minutes = delayP90Minutes; }

    public String getDisruptionRiskLevel() { return disruptionRiskLevel; }
    public void setDisruptionRiskLevel(String disruptionRiskLevel) { this.disruptionRiskLevel = disruptionRiskLevel; }

    public List<String> getRiskFactors() { return riskFactors; }
    public void setRiskFactors(List<String> riskFactors) { this.riskFactors = riskFactors; }

    public List<ShapFactorDto> getTopContributingFactors() { return topContributingFactors; }
    public void setTopContributingFactors(List<ShapFactorDto> topContributingFactors) { this.topContributingFactors = topContributingFactors; }

    public List<String> getWeatherHazards() { return weatherHazards; }
    public void setWeatherHazards(List<String> weatherHazards) { this.weatherHazards = weatherHazards; }

    public boolean isExperimental() { return isExperimental; }
    public void setExperimental(boolean experimental) { isExperimental = experimental; }

    public boolean isDurationImputed() { return isDurationImputed; }
    public void setDurationImputed(boolean durationImputed) { isDurationImputed = durationImputed; }

    public boolean isDistanceImputed() { return isDistanceImputed; }
    public void setDistanceImputed(boolean distanceImputed) { isDistanceImputed = distanceImputed; }

    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String disclaimer) { this.disclaimer = disclaimer; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public Double getInferenceLatencyMs() { return inferenceLatencyMs; }
    public void setInferenceLatencyMs(Double inferenceLatencyMs) { this.inferenceLatencyMs = inferenceLatencyMs; }
}
