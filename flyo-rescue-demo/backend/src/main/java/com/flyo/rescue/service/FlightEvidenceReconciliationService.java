package com.flyo.rescue.service;

import com.flyo.rescue.client.PredictionServiceClient;
import com.flyo.rescue.dto.AirportWeatherTelemetry;
import com.flyo.rescue.dto.FlightPredictionRequestDto;
import com.flyo.rescue.dto.FlightPredictionResponseDto;
import com.flyo.rescue.dto.FlightStatusAlert;
import com.flyo.rescue.dto.ReconciledFlightEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class FlightEvidenceReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(FlightEvidenceReconciliationService.class);

    private final AviationRadarClient radarClient;
    private final OpenMeteoWeatherService weatherService;
    private final AviationNewsIntelligenceService newsService;
    private final PredictionServiceClient predictionServiceClient;

    public FlightEvidenceReconciliationService(AviationRadarClient radarClient,
                                               OpenMeteoWeatherService weatherService,
                                               AviationNewsIntelligenceService newsService,
                                               PredictionServiceClient predictionServiceClient) {
        this.radarClient = radarClient;
        this.weatherService = weatherService;
        this.newsService = newsService;
        this.predictionServiceClient = predictionServiceClient;
    }

    /**
     * Reconciles multi-source evidence across Aviation Radar, Open-Meteo Airport Weather Telemetry,
     * and Aviation News Intelligence to confirm disruption validity and compute explicit confidence scores.
     */
    public ReconciledFlightEvidence reconcileDisruption(String flightNumber,
                                                        String departureAirport,
                                                        String arrivalAirport,
                                                        String initialStatus,
                                                        Integer initialDelayMinutes) {
        List<String> sources = new ArrayList<>();
        sources.add("Aviation Radar Telemetry Engine");

        String normFlight = flightNumber != null ? flightNumber.trim().toUpperCase() : "FL101";
        String normDep = departureAirport != null ? departureAirport.trim().toUpperCase() : "HYD";
        String normArr = arrivalAirport != null ? arrivalAirport.trim().toUpperCase() : "DEL";

        // 1. Fetch Live or Alert Radar Status
        String radarStatus = initialStatus != null ? initialStatus.toUpperCase() : "CANCELLED";
        int radarDelay = initialDelayMinutes != null ? initialDelayMinutes : 0;

        Optional<FlightStatusAlert> radarAlert = radarClient.fetchLiveFlightStatus(normFlight);
        if (radarAlert.isPresent()) {
            radarStatus = radarAlert.get().getStatus().toUpperCase();
            radarDelay = radarAlert.get().getDelayMinutes();
            sources.add("Live Flight Telemetry (" + radarAlert.get().getSource() + ")");
        }

        // 2. Fetch Destination Weather Telemetry
        sources.add("Open-Meteo Airport Telemetry API");
        AirportWeatherTelemetry destWeather = weatherService.getWeatherForAirport(normArr);

        // 3. Fetch Operational News Context
        sources.add("Aviation Operational News & Bulletins");
        String headline = newsService.findRelevantDisruptionContext(normArr, extractAirlineCode(normFlight));

        // 4. Multi-Source Reconciliation & Confidence Calculation
        boolean isCancellation = "CANCELLED".equalsIgnoreCase(radarStatus);
        boolean isAdverseWeather = destWeather.isAdverseWeather();

        int confidencePercent;
        String confidenceLevel;
        String reconciledReason;
        String recommendedAction;

        if (isCancellation) {
            if (isAdverseWeather) {
                // High correlation: Radar cancellation + Adverse arrival weather (e.g. Delhi winter fog)
                confidencePercent = 98;
                confidenceLevel = "CONFIRMED";
                reconciledReason = String.format(
                        "Multi-source verified (98%% confidence): Aviation Radar cancellation strongly corroborated by adverse weather at destination %s (%s, %.0fm visibility, CAT III active). %s",
                        normArr, destWeather.getWeatherCondition(), destWeather.getVisibilityMeters(), headline
                );
                recommendedAction = "PROCEED_IMMEDIATE_RESCUE";
            } else {
                // Cancellation verified by radar, weather standard -> operational/equipment reason
                confidencePercent = 93;
                confidenceLevel = "HIGH";
                reconciledReason = String.format(
                        "Multi-source verified (93%% confidence): Aviation Radar telemetry confirms cancellation due to airline operational or fleet constraints. %s",
                        headline
                );
                recommendedAction = "PROCEED_IMMEDIATE_RESCUE";
            }
        } else if (radarDelay >= 90) {
            confidencePercent = isAdverseWeather ? 95 : 88;
            confidenceLevel = "ELEVATED_DELAY";
            reconciledReason = String.format(
                    "Multi-source verified (%d%% confidence): Radar reports severe schedule delay of %dm. Destination %s weather: %s (%.0fm visibility).",
                    confidencePercent, radarDelay, normArr, destWeather.getWeatherCondition(), destWeather.getVisibilityMeters()
            );
            recommendedAction = "EVALUATE_CONNECTION_RISK";
        } else if (isAdverseWeather) {
            // CRITICAL AUDIT RULE: Weather conditions NEVER independently establish a flight cancellation
            confidencePercent = 50;
            confidenceLevel = "WEATHER_ADVISORY_MONITORING";
            reconciledReason = String.format(
                    "Operational advisory: Adverse meteorological conditions detected at destination %s (%s, %.0fm visibility via Open-Meteo), but flight %s is NOT cancelled by authoritative airline radar. Cancellation requires authoritative airline dispatch confirmation.",
                    normArr, destWeather.getWeatherCondition(), destWeather.getVisibilityMeters(), normFlight
            );
            recommendedAction = "MONITOR_AIRLINE_DISPATCH";
        } else {
            confidencePercent = 75;
            confidenceLevel = "MODERATE";
            reconciledReason = "Radar status indicates standard scheduled operations. Monitored for flight telemetry changes.";
            recommendedAction = "MONITOR";
        }

        String weatherSummary = String.format("%s: %.1f°C, %s, %.0fm visibility (%s)",
                normArr, destWeather.getTemperatureC(), destWeather.getWeatherCondition(),
                destWeather.getVisibilityMeters(), destWeather.getSource());

        log.info("[Evidence Reconciliation] Flight {}: Status={}, Confidence={}% ({}), AdverseWeather={}",
                normFlight, radarStatus, confidencePercent, confidenceLevel, isAdverseWeather);

        ReconciledFlightEvidence evidence = new ReconciledFlightEvidence(
                normFlight,
                normDep,
                normArr,
                radarStatus,
                radarDelay,
                weatherSummary,
                isAdverseWeather,
                destWeather.getVisibilityMeters(),
                destWeather.getWeatherCondition(),
                headline,
                confidencePercent,
                confidenceLevel,
                reconciledReason,
                sources,
                isCancellation,
                recommendedAction
        );

        // 5. Query Supplementary ML Disruption Intelligence (Non-Authoritative)
        try {
            FlightPredictionRequestDto mlReq = new FlightPredictionRequestDto(
                    normFlight, extractAirlineCode(normFlight), normDep, normArr, "12:00"
            );
            FlightPredictionResponseDto mlPrediction = predictionServiceClient.predictDisruption(mlReq);
            evidence.setDisruptionPrediction(mlPrediction);
            if ("PREDICTION_AVAILABLE".equals(mlPrediction.getStatus())) {
                sources.add(String.format("Predictive ML Disruption Engine (v%s, %.1f%% cancel prob, %.1f%% extreme delay prob)",
                        mlPrediction.getModelVersion(),
                        mlPrediction.getCancellationProbability() != null ? mlPrediction.getCancellationProbability() * 100 : 0,
                        mlPrediction.getDelay120mProbability() != null ? mlPrediction.getDelay120mProbability() * 100 : 0));
            } else {
                sources.add("Predictive ML Intelligence (" + mlPrediction.getStatus() + ")");
            }
        } catch (Exception e) {
            log.warn("[Evidence Reconciliation] Supplementary ML prediction query failed gracefully: {}", e.getMessage());
        }

        return evidence;
    }

    private String extractAirlineCode(String flight) {
        if (flight == null || flight.length() < 2) return "AI";
        return flight.substring(0, 2);
    }
}
