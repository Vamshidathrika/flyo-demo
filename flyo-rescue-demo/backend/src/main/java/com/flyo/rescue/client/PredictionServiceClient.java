package com.flyo.rescue.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.FlightPredictionRequestDto;
import com.flyo.rescue.dto.FlightPredictionResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class PredictionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PredictionServiceClient.class);

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final Set<String> INDIAN_AIRPORTS = Set.of(
            "DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "AMD", "GOI", "PNQ", "COK",
            "ATQ", "IXC", "JAI", "LKO", "GAU", "PAT", "VNS", "BBI", "TRV", "IXR"
    );

    private static final Set<String> INDIAN_CARRIERS = Set.of(
            "6E", "AI", "UK", "SG", "QP", "G8", "IX"
    );

    private final boolean enabled;
    private final String baseUrl;
    private final long timeoutMs;
    private final int failureThreshold;
    private final long recoveryTimeoutMs;
    private final String monitoringApiKey;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Stateful Circuit Breaker state
    private final AtomicReference<CircuitState> circuitState = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTimestamp = new AtomicLong(0);

    @org.springframework.beans.factory.annotation.Autowired
    public PredictionServiceClient(
            @Value("${flyo.ml.prediction.enabled:true}") boolean enabled,
            @Value("${flyo.ml.prediction.base-url:http://localhost:8000}") String baseUrl,
            @Value("${flyo.ml.prediction.timeout-ms:1500}") long timeoutMs,
            @Value("${flyo.ml.prediction.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${flyo.ml.prediction.circuit-breaker.cooldown-ms:5000}") long recoveryTimeoutMs,
            @Value("${flyo.ml.monitoring.api-key:flyo-monitoring-secret-key-2026}") String monitoringApiKey) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeoutMs = timeoutMs;
        this.failureThreshold = failureThreshold;
        this.recoveryTimeoutMs = recoveryTimeoutMs;
        this.monitoringApiKey = monitoringApiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    // Convenience constructor for tests
    public PredictionServiceClient(
            boolean enabled,
            String baseUrl,
            long timeoutMs,
            int failureThreshold,
            long recoveryTimeoutMs) {
        this(enabled, baseUrl, timeoutMs, failureThreshold, recoveryTimeoutMs, "flyo-monitoring-secret-key-2026");
    }

    public PredictionServiceClient(boolean enabled, String baseUrl, long timeoutMs) {
        this(enabled, baseUrl, timeoutMs, 3, 5000, "flyo-monitoring-secret-key-2026");
    }

    public CircuitState getCircuitState() {
        return circuitState.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public void resetCircuitBreaker() {
        circuitState.set(CircuitState.CLOSED);
        consecutiveFailures.set(0);
        lastFailureTimestamp.set(0);
        log.info("[CircuitBreaker] Circuit breaker manually reset to CLOSED.");
    }

    public void forceOpen() {
        circuitState.set(CircuitState.OPEN);
        lastFailureTimestamp.set(System.currentTimeMillis());
        log.warn("[CircuitBreaker] Circuit breaker forced to OPEN state.");
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        if (circuitState.get() == CircuitState.HALF_OPEN) {
            if (circuitState.compareAndSet(CircuitState.HALF_OPEN, CircuitState.CLOSED)) {
                log.info("[CircuitBreaker] Health probe succeeded. State transitioned HALF_OPEN -> CLOSED (Full service recovery)");
            }
        }
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTimestamp.set(System.currentTimeMillis());

        if (circuitState.get() == CircuitState.HALF_OPEN) {
            circuitState.set(CircuitState.OPEN);
            log.warn("[CircuitBreaker] Probe in HALF_OPEN failed. State transitioned HALF_OPEN -> OPEN (Cooldown window reset)");
        } else if (failures >= failureThreshold) {
            if (circuitState.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                log.warn("[CircuitBreaker] Consecutive failures reached ({}/{}). State transitioned CLOSED -> OPEN (Circuit tripped)",
                        failures, failureThreshold);
            }
        }
    }

    /**
     * Queries the FastAPI ML inference service with a full 3-state Circuit Breaker (CLOSED, OPEN, HALF_OPEN),
     * geofencing pre-checks, and clean fallback to deterministic intelligence.
     */
    public FlightPredictionResponseDto predictDisruption(FlightPredictionRequestDto request) {
        String flightNum = request.getFlightNumber() != null ? request.getFlightNumber() : "UNKNOWN";
        String carrier = request.getCarrierCode() != null ? request.getCarrierCode().toUpperCase() : "UNKNOWN";
        String origin = request.getOriginAirport() != null ? request.getOriginAirport().toUpperCase() : "UNKNOWN";
        String dest = request.getDestAirport() != null ? request.getDestAirport().toUpperCase() : "UNKNOWN";

        // 1. Check if disabled by configuration
        if (!enabled) {
            log.info("[ML Client] Prediction service is disabled via configuration. Returning MODEL_UNAVAILABLE.");
            return FlightPredictionResponseDto.fallback(
                    flightNum, carrier, origin, dest,
                    "MODEL_UNAVAILABLE",
                    "Prediction service is disabled via configuration. Deterministic intelligence is authoritative."
            );
        }

        // 2. Airspace geofencing check (Indian domestic or Indian carrier)
        if (INDIAN_AIRPORTS.contains(origin) || INDIAN_AIRPORTS.contains(dest) || INDIAN_CARRIERS.contains(carrier)) {
            log.info("[ML Client] Flight {} operates in Indian airspace. Returning UNSUPPORTED_AIRSPACE without network call.", flightNum);
            FlightPredictionResponseDto resp = FlightPredictionResponseDto.fallback(
                    flightNum, carrier, origin, dest,
                    "UNSUPPORTED_AIRSPACE",
                    "Flight operates in Indian airspace. Flyo v2 ML disruption models were trained exclusively on US domestic flights (BTS)."
            );
            resp.getRiskFactors().add("UNSUPPORTED_AIRSPACE: Indian airspace not covered by BTS training data");
            return resp;
        }

        // 3. Circuit Breaker Inspection
        CircuitState currentState = circuitState.get();
        if (currentState == CircuitState.OPEN) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastFailureTimestamp.get();
            if (elapsed >= recoveryTimeoutMs) {
                if (circuitState.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    log.info("[CircuitBreaker] Recovery cooldown elapsed ({}ms >= {}ms). Transitioning OPEN -> HALF_OPEN (Allowing trial probe)",
                            elapsed, recoveryTimeoutMs);
                } else {
                    return FlightPredictionResponseDto.fallback(
                            flightNum, carrier, origin, dest,
                            "MODEL_UNAVAILABLE",
                            "Circuit breaker is OPEN. Cooldown active, fast-failing to preserve thread pool."
                    );
                }
            } else {
                log.warn("[CircuitBreaker] Circuit is OPEN (consecutive failures: {}). Fast-failing prediction for flight {} without remote call.",
                        consecutiveFailures.get(), flightNum);
                return FlightPredictionResponseDto.fallback(
                        flightNum, carrier, origin, dest,
                        "MODEL_UNAVAILABLE",
                        "Circuit breaker is OPEN (service degraded). Fast-failing request to protect threads. Deterministic engine authoritative."
                );
            }
        }

        // 4. Remote HTTP Inference with 1,500ms timeout
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/predict/flight"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                recordSuccess();
                return objectMapper.readValue(httpResponse.body(), FlightPredictionResponseDto.class);
            } else {
                log.warn("[ML Client] FastAPI inference returned non-200 status: {} - {}", httpResponse.statusCode(), httpResponse.body());
                recordFailure();
                return FlightPredictionResponseDto.fallback(
                        flightNum, carrier, origin, dest,
                        "MODEL_UNAVAILABLE",
                        "Prediction service returned HTTP " + httpResponse.statusCode() + ". Falling back to deterministic engine."
                );
            }
        } catch (HttpTimeoutException te) {
            log.warn("[ML Client] Timeout ({}ms exceeded) connecting to FastAPI ML service: {}", timeoutMs, te.getMessage());
            recordFailure();
            return FlightPredictionResponseDto.fallback(
                    flightNum, carrier, origin, dest,
                    "MODEL_UNAVAILABLE",
                    "Prediction service timed out (" + timeoutMs + "ms). Fallback to deterministic intelligence active."
            );
        } catch (Exception e) {
            log.warn("[ML Client] Error connecting to FastAPI ML service ({}): {}", baseUrl, e.getMessage());
            recordFailure();
            return FlightPredictionResponseDto.fallback(
                    flightNum, carrier, origin, dest,
                    "MODEL_UNAVAILABLE",
                    "Prediction service unavailable (" + e.getClass().getSimpleName() + "). Deterministic risk engine remains authoritative."
            );
        }
    }

    public boolean isServiceHealthy() {
        if (!enabled) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health/live"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Retrieves aggregated ML monitoring, telemetry, drift, data quality, and active alerts
     * from the FastAPI monitoring layer for operator dashboard observability.
     */
    public java.util.Map<String, Object> getMonitoringSummary() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("circuitState", circuitState.get().name());
        result.put("consecutiveFailures", consecutiveFailures.get());
        result.put("mlServiceHealthy", isServiceHealthy());

        if (!enabled) {
            result.put("status", "DISABLED");
            result.put("message", "Prediction service is disabled via configuration.");
            return result;
        }

        try {
            // 1. Fetch Health
            fetchAndPutMonitoringEndpoint("/monitoring/health", "health", result);
            // 2. Fetch Prediction Summary
            fetchAndPutMonitoringEndpoint("/monitoring/predictions/summary", "predictions", result);
            // 3. Fetch Data Quality (imputation rates, range checks)
            fetchAndPutMonitoringEndpoint("/monitoring/data-quality?limit=500", "dataQuality", result);
            // 4. Fetch Drift (PSI & KS checks)
            fetchAndPutMonitoringEndpoint("/monitoring/drift?limit=500", "drift", result);
            // 5. Fetch Active Alerts
            fetchAndPutMonitoringEndpoint("/monitoring/alerts?limit=50", "alerts", result);
            // 6. Fetch Governance & Registry
            fetchAndPutMonitoringEndpoint("/monitoring/governance", "governance", result);
            // 7. Fetch Model Performance
            fetchAndPutMonitoringEndpoint("/monitoring/model-performance", "performance", result);
        } catch (Exception e) {
            log.warn("[ML Client] Failed to fetch complete monitoring summary: {}", e.getMessage());
            result.put("error", "Failed to retrieve complete monitoring telemetry: " + e.getMessage());
        }

        return result;
    }

    private void fetchAndPutMonitoringEndpoint(String path, String key, java.util.Map<String, Object> targetMap) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("X-Flyo-Monitoring-Key", monitoringApiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                targetMap.put(key, objectMapper.readValue(resp.body(), Object.class));
            } else {
                targetMap.put(key, java.util.Map.of("status", "UNAVAILABLE", "code", resp.statusCode()));
            }
        } catch (Exception ex) {
            log.debug("[ML Client] Error fetching {}: {}", path, ex.getMessage());
            targetMap.put(key, java.util.Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }
}
