package com.flyo.rescue.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ReconciledFlightEvidence {

    private String flightNumber;
    private String departureAirport;
    private String arrivalAirport;
    private String radarStatus;
    private int radarDelayMinutes;
    private String weatherSummary;
    private boolean adverseWeatherDetected;
    private double destinationVisibilityMeters;
    private String destinationWeatherCondition;
    private String intelligenceHeadline;
    private int confidencePercent;
    private String confidenceLevel; // CONFIRMED, HIGH, MODERATE, LOW
    private String reconciledReason;
    private List<String> sourcesEvaluated = new ArrayList<>();
    private boolean cancellationConfirmed;
    private String recommendedAction;
    private String telemetryReliabilityTier = "AUTHORITATIVE_PRIMARY";
    private String weatherGrade = "GENERAL_METEOROLOGICAL_OPEN_METEO";
    private LocalDateTime evaluatedAt;
    private FlightPredictionResponseDto disruptionPrediction;

    public ReconciledFlightEvidence() {
        this.evaluatedAt = LocalDateTime.now();
    }

    public ReconciledFlightEvidence(String flightNumber, String departureAirport, String arrivalAirport,
                                    String radarStatus, int radarDelayMinutes, String weatherSummary,
                                    boolean adverseWeatherDetected, double destinationVisibilityMeters,
                                    String destinationWeatherCondition, String intelligenceHeadline,
                                    int confidencePercent, String confidenceLevel, String reconciledReason,
                                    List<String> sourcesEvaluated, boolean cancellationConfirmed,
                                    String recommendedAction) {
        this.flightNumber = flightNumber;
        this.departureAirport = departureAirport;
        this.arrivalAirport = arrivalAirport;
        this.radarStatus = radarStatus;
        this.radarDelayMinutes = radarDelayMinutes;
        this.weatherSummary = weatherSummary;
        this.adverseWeatherDetected = adverseWeatherDetected;
        this.destinationVisibilityMeters = destinationVisibilityMeters;
        this.destinationWeatherCondition = destinationWeatherCondition;
        this.intelligenceHeadline = intelligenceHeadline;
        this.confidencePercent = confidencePercent;
        this.confidenceLevel = confidenceLevel;
        this.reconciledReason = reconciledReason;
        this.sourcesEvaluated = sourcesEvaluated != null ? sourcesEvaluated : new ArrayList<>();
        this.cancellationConfirmed = cancellationConfirmed;
        this.recommendedAction = recommendedAction;
        this.evaluatedAt = LocalDateTime.now();
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
    }

    public String getDepartureAirport() {
        return departureAirport;
    }

    public void setDepartureAirport(String departureAirport) {
        this.departureAirport = departureAirport;
    }

    public String getArrivalAirport() {
        return arrivalAirport;
    }

    public void setArrivalAirport(String arrivalAirport) {
        this.arrivalAirport = arrivalAirport;
    }

    public String getRadarStatus() {
        return radarStatus;
    }

    public void setRadarStatus(String radarStatus) {
        this.radarStatus = radarStatus;
    }

    public int getRadarDelayMinutes() {
        return radarDelayMinutes;
    }

    public void setRadarDelayMinutes(int radarDelayMinutes) {
        this.radarDelayMinutes = radarDelayMinutes;
    }

    public String getWeatherSummary() {
        return weatherSummary;
    }

    public void setWeatherSummary(String weatherSummary) {
        this.weatherSummary = weatherSummary;
    }

    public boolean isAdverseWeatherDetected() {
        return adverseWeatherDetected;
    }

    public void setAdverseWeatherDetected(boolean adverseWeatherDetected) {
        this.adverseWeatherDetected = adverseWeatherDetected;
    }

    public double getDestinationVisibilityMeters() {
        return destinationVisibilityMeters;
    }

    public void setDestinationVisibilityMeters(double destinationVisibilityMeters) {
        this.destinationVisibilityMeters = destinationVisibilityMeters;
    }

    public String getDestinationWeatherCondition() {
        return destinationWeatherCondition;
    }

    public void setDestinationWeatherCondition(String destinationWeatherCondition) {
        this.destinationWeatherCondition = destinationWeatherCondition;
    }

    public String getIntelligenceHeadline() {
        return intelligenceHeadline;
    }

    public void setIntelligenceHeadline(String intelligenceHeadline) {
        this.intelligenceHeadline = intelligenceHeadline;
    }

    public int getConfidencePercent() {
        return confidencePercent;
    }

    public void setConfidencePercent(int confidencePercent) {
        this.confidencePercent = confidencePercent;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public void setConfidenceLevel(String confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    public String getReconciledReason() {
        return reconciledReason;
    }

    public void setReconciledReason(String reconciledReason) {
        this.reconciledReason = reconciledReason;
    }

    public List<String> getSourcesEvaluated() {
        return sourcesEvaluated;
    }

    public void setSourcesEvaluated(List<String> sourcesEvaluated) {
        this.sourcesEvaluated = sourcesEvaluated;
    }

    public boolean isCancellationConfirmed() {
        return cancellationConfirmed;
    }

    public void setCancellationConfirmed(boolean cancellationConfirmed) {
        this.cancellationConfirmed = cancellationConfirmed;
    }

    public String getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(String recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public String getTelemetryReliabilityTier() {
        return telemetryReliabilityTier;
    }

    public void setTelemetryReliabilityTier(String telemetryReliabilityTier) {
        this.telemetryReliabilityTier = telemetryReliabilityTier;
    }

    public String getWeatherGrade() {
        return weatherGrade;
    }

    public void setWeatherGrade(String weatherGrade) {
        this.weatherGrade = weatherGrade;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public FlightPredictionResponseDto getDisruptionPrediction() {
        return disruptionPrediction;
    }

    public void setDisruptionPrediction(FlightPredictionResponseDto disruptionPrediction) {
        this.disruptionPrediction = disruptionPrediction;
    }
}
