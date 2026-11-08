package com.flyo.rescue.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.config.AviationRadarConfig;
import com.flyo.rescue.dto.FlightStatusAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Service
public class AviationRadarClient {

    private static final Logger log = LoggerFactory.getLogger(AviationRadarClient.class);

    private final AviationRadarConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public AviationRadarClient(AviationRadarConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Queries the external aviation radar feed for current telemetry on a specific flight.
     * Returns a FlightStatusAlert if the flight has active delay or cancellation status.
     */
    public Optional<FlightStatusAlert> fetchLiveFlightStatus(String flightIata) {
        if (flightIata == null || flightIata.isBlank()) {
            return Optional.empty();
        }

        String normalizedFlight = flightIata.replaceAll("\\s+", "").toUpperCase();
        log.info("[Aviation Radar] Polling live status for flight {} via provider '{}'", normalizedFlight, config.getProvider());

        if (config.isMockMode()) {
            return getMockFlightStatus(normalizedFlight);
        }

        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.warn("[Aviation Radar] Provider '{}' unconfigured (missing API key). Returning empty status (fail closed).", config.getProvider());
            return Optional.empty();
        }

        try {
            // Live HTTP Query (AviationStack format: /flights?access_key=...&flight_iata=...)
            String targetUrl = String.format("%s/flights?access_key=%s&flight_iata=%s",
                    config.getBaseUrl(), config.getApiKey(), normalizedFlight.replace("-", ""));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseAviationStackResponse(response.body(), normalizedFlight);
            } else {
                log.warn("[Aviation Radar] Provider returned HTTP {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }
        } catch (Exception e) {
            log.error("[Aviation Radar] Connection error querying radar feed for {}: {}", normalizedFlight, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<FlightStatusAlert> parseAviationStackResponse(String jsonBody, String flightIata) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode first = data.get(0);
                String flightStatus = first.path("flight_status").asText("scheduled").toUpperCase();
                int delay = first.path("departure").path("delay").asInt(0);

                if ("CANCELLED".equalsIgnoreCase(flightStatus) || delay >= 90) {
                    return Optional.of(new FlightStatusAlert(
                            flightIata,
                            "CANCELLED".equalsIgnoreCase(flightStatus) ? "CANCELLED" : "DELAYED",
                            delay,
                            "Aviation Radar live telemetry: " + flightStatus + " (Delay: " + delay + "m)",
                            config.getProvider()
                    ));
                }
            }
        } catch (Exception e) {
            log.error("[Aviation Radar] Error parsing radar JSON response: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Deterministic mock telemetry provider for local development, demoing, and verification suites.
     */
    private Optional<FlightStatusAlert> getMockFlightStatus(String flightIata) {
        String clean = flightIata.replace("-", "").toUpperCase();
        if (clean.equals("AI839")) {
            return Optional.of(new FlightStatusAlert(
                    "AI-839",
                    "CANCELLED",
                    180,
                    "Dense fog & zero-visibility CAT III ops at DEL (Mock Radar)",
                    "Aviation Radar (Mock)"
            ));
        } else if (clean.equals("6E402")) {
            return Optional.of(new FlightStatusAlert(
                    "6E-402",
                    "DELAYED",
                    120,
                    "Technical inspection & incoming aircraft turnaround delay",
                    "Aviation Radar (Mock)"
            ));
        }
        return Optional.empty();
    }
}
