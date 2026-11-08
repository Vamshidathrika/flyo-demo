package com.flyo.rescue.controller;

import com.flyo.rescue.client.PredictionServiceClient;
import com.flyo.rescue.dto.*;
import com.flyo.rescue.service.AviationNewsIntelligenceService;
import com.flyo.rescue.service.FlightEvidenceReconciliationService;
import com.flyo.rescue.service.OpenMeteoWeatherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AviationIntelligenceController {

    private final OpenMeteoWeatherService weatherService;
    private final AviationNewsIntelligenceService newsService;
    private final FlightEvidenceReconciliationService reconciliationService;
    private final PredictionServiceClient predictionServiceClient;

    public AviationIntelligenceController(OpenMeteoWeatherService weatherService,
                                          AviationNewsIntelligenceService newsService,
                                          FlightEvidenceReconciliationService reconciliationService,
                                          PredictionServiceClient predictionServiceClient) {
        this.weatherService = weatherService;
        this.newsService = newsService;
        this.reconciliationService = reconciliationService;
        this.predictionServiceClient = predictionServiceClient;
    }

    @GetMapping("/weather/{airportCode}")
    public ResponseEntity<AirportWeatherTelemetry> getWeather(@PathVariable String airportCode) {
        AirportWeatherTelemetry telemetry = weatherService.getWeatherForAirport(airportCode);
        return ResponseEntity.ok(telemetry);
    }

    @GetMapping("/intelligence/news")
    public ResponseEntity<List<AviationNewsIntelligenceService.AviationHeadline>> getNewsHeadlines() {
        return ResponseEntity.ok(newsService.getLatestHeadlines());
    }

    @GetMapping("/intelligence/reconcile")
    public ResponseEntity<ReconciledFlightEvidence> reconcileFlight(
            @RequestParam(defaultValue = "FL101") String flightNumber,
            @RequestParam(defaultValue = "HYD") String departureAirport,
            @RequestParam(defaultValue = "DEL") String arrivalAirport,
            @RequestParam(defaultValue = "CANCELLED") String status,
            @RequestParam(required = false, defaultValue = "0") Integer delayMinutes) {
        ReconciledFlightEvidence evidence = reconciliationService.reconcileDisruption(
                flightNumber, departureAirport, arrivalAirport, status, delayMinutes
        );
        return ResponseEntity.ok(evidence);
    }

    @GetMapping("/intelligence/predict")
    public ResponseEntity<FlightPredictionResponseDto> predictFlight(
            @RequestParam(defaultValue = "AA100") String flightNumber,
            @RequestParam(required = false) String carrierCode,
            @RequestParam(defaultValue = "ORD") String originAirport,
            @RequestParam(defaultValue = "DFW") String destAirport,
            @RequestParam(defaultValue = "14:30") String scheduledDepTime,
            @RequestParam(required = false) String scheduledArrTime,
            @RequestParam(required = false) String departureDate,
            @RequestParam(required = false) Double distanceMiles,
            @RequestParam(required = false) Double scheduledElapsedMinutes) {

        String derivedCarrier = carrierCode;
        if ((derivedCarrier == null || derivedCarrier.isBlank()) && flightNumber != null && flightNumber.length() >= 2) {
            derivedCarrier = flightNumber.substring(0, 2).toUpperCase();
        }

        FlightPredictionRequestDto request = new FlightPredictionRequestDto(
                flightNumber, derivedCarrier, originAirport, destAirport, scheduledDepTime
        );
        request.setScheduledArrTime(scheduledArrTime);
        request.setDepartureDate(departureDate);
        request.setDistanceMiles(distanceMiles);
        request.setScheduledElapsedMinutes(scheduledElapsedMinutes);

        FlightPredictionResponseDto response = predictionServiceClient.predictDisruption(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/intelligence/predict")
    public ResponseEntity<FlightPredictionResponseDto> predictFlightPost(
            @RequestBody FlightPredictionRequestDto request) {
        FlightPredictionResponseDto response = predictionServiceClient.predictDisruption(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/intelligence/predict/delay-forecast")
    public ResponseEntity<DelayForecastResponseDto> predictDelayForecast(
            @RequestBody DelayForecastRequestDto request) {
        DelayForecastResponseDto response = predictionServiceClient.predictDelayForecast(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/intelligence/predict/delay-forecast")
    public ResponseEntity<DelayForecastResponseDto> getDelayForecast(
            @RequestParam(defaultValue = "6E-501") String flightNumber,
            @RequestParam(defaultValue = "6E") String carrierCode,
            @RequestParam(defaultValue = "DEL") String originAirport,
            @RequestParam(defaultValue = "BOM") String destAirport,
            @RequestParam(defaultValue = "07:15") String scheduledDepTime,
            @RequestParam(defaultValue = "T_MINUS_2H") String horizon) {
        DelayForecastRequestDto request = new DelayForecastRequestDto(
                flightNumber, carrierCode, originAirport, destAirport, scheduledDepTime
        );
        request.setOperationalHorizon(horizon);
        return ResponseEntity.ok(predictionServiceClient.predictDelayForecast(request));
    }

    @PostMapping("/intelligence/predict/connection-risk")
    public ResponseEntity<ConnectionRiskResponseDto> evaluateConnectionRisk(
            @RequestBody ConnectionRiskRequestDto request) {
        ConnectionRiskResponseDto response = predictionServiceClient.evaluateConnectionRisk(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/intelligence/predict/connection-risk")
    public ResponseEntity<ConnectionRiskResponseDto> getConnectionRisk(
            @RequestParam(defaultValue = "DEL") String airportCode,
            @RequestParam(defaultValue = "6E-501") String inboundFlight,
            @RequestParam(defaultValue = "6E-204") String outboundFlight,
            @RequestParam(defaultValue = "55") Integer scheduledConnectionMinutes,
            @RequestParam(defaultValue = "35.0") Double predictedInboundDelayMinutes) {
        ConnectionRiskRequestDto request = new ConnectionRiskRequestDto(
                airportCode, inboundFlight, outboundFlight, scheduledConnectionMinutes, predictedInboundDelayMinutes
        );
        return ResponseEntity.ok(predictionServiceClient.evaluateConnectionRisk(request));
    }

    @PostMapping("/intelligence/predict/passenger-impact")
    public ResponseEntity<PassengerImpactResponseDto> estimatePassengerImpact(
            @RequestBody PassengerImpactRequestDto request) {
        PassengerImpactResponseDto response = predictionServiceClient.estimatePassengerImpact(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/intelligence/predict/passenger-impact")
    public ResponseEntity<PassengerImpactResponseDto> getPassengerImpact(
            @RequestParam(defaultValue = "6E-501") String flightNumber,
            @RequestParam(defaultValue = "6E") String carrierCode,
            @RequestParam(defaultValue = "DEL") String originAirport,
            @RequestParam(defaultValue = "BOM") String destAirport,
            @RequestParam(defaultValue = "0.39") Double disruptionProbability,
            @RequestParam(defaultValue = "50.0") Double expectedDelayMinutes) {
        PassengerImpactRequestDto request = new PassengerImpactRequestDto(
                flightNumber, carrierCode, originAirport, destAirport, disruptionProbability, expectedDelayMinutes
        );
        return ResponseEntity.ok(predictionServiceClient.estimatePassengerImpact(request));
    }

    @GetMapping("/intelligence/monitoring/leaderboard")
    public ResponseEntity<Map<String, Object>> getRiskLeaderboard() {
        return ResponseEntity.ok(predictionServiceClient.getRiskLeaderboard());
    }

    @GetMapping("/intelligence/monitoring/heatmap")
    public ResponseEntity<Map<String, Object>> getAirportHeatmap() {
        return ResponseEntity.ok(predictionServiceClient.getAirportHeatmap());
    }

    @GetMapping("/intelligence/monitoring/summary")
    public ResponseEntity<Map<String, Object>> getMonitoringSummary() {
        return ResponseEntity.ok(predictionServiceClient.getMonitoringSummary());
    }
}
