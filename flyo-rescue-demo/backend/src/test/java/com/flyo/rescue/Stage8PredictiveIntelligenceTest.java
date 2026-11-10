package com.flyo.rescue;

import com.flyo.rescue.client.PredictionServiceClient;
import com.flyo.rescue.dto.*;
import com.flyo.rescue.model.OperatorAccount;
import com.flyo.rescue.repository.OperatorAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class Stage8PredictiveIntelligenceTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PredictionServiceClient predictionClient;

    @Autowired
    private OperatorAccountRepository operatorAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        if (operatorAccountRepository.findByUsername("admin").isEmpty()) {
            operatorAccountRepository.save(new OperatorAccount(
                    "USR-2", "admin", passwordEncoder.encode("AdminSecret123!"),
                    "System Admin", "ROLE_ADMIN", true, "919999999999", "tenant_default", "*"
            ));
        }
    }

    @Test
    @DisplayName("Stage 8: Delay Forecast endpoint returns quantile intervals and risk tier")
    void testDelayForecastEndpoint() {
        ResponseEntity<DelayForecastResponseDto> response = restTemplate
                .withBasicAuth("admin", "AdminSecret123!")
                .getForEntity(
                        "/api/intelligence/predict/delay-forecast?flightNumber=6E-501&carrierCode=6E&originAirport=DEL&destAirport=BOM&horizon=T_MINUS_2H",
                        DelayForecastResponseDto.class
                );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DelayForecastResponseDto dto = response.getBody();
        assertNotNull(dto);
        assertEquals("6E-501", dto.getFlightNumber());
        assertEquals("T_MINUS_2H", dto.getOperationalHorizon());
        assertNotNull(dto.getDelayRiskTier());
        assertNotNull(dto.getHorizonExplanation());
    }

    @Test
    @DisplayName("Stage 8: Connection Risk endpoint calculates MCT and recommended action")
    void testConnectionRiskEndpoint() {
        ResponseEntity<ConnectionRiskResponseDto> response = restTemplate
                .withBasicAuth("admin", "AdminSecret123!")
                .getForEntity(
                        "/api/intelligence/predict/connection-risk?airportCode=DEL&inboundFlight=6E-501&outboundFlight=6E-204&scheduledConnectionMinutes=55&predictedInboundDelayMinutes=35.0",
                        ConnectionRiskResponseDto.class
                );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ConnectionRiskResponseDto dto = response.getBody();
        assertNotNull(dto);
        assertEquals("DEL", dto.getAirportCode());
        assertEquals("6E-501", dto.getInboundFlightNumber());
        assertEquals("6E-204", dto.getOutboundFlightNumber());
        assertNotNull(dto.getEffectiveMctMinutes());
        assertNotNull(dto.getRecommendedAction());
        assertNotNull(dto.getRiskTier());
    }

    @Test
    @DisplayName("Stage 8: Passenger Impact endpoint calculates seat sizing and rebooking demand")
    void testPassengerImpactEndpoint() {
        ResponseEntity<PassengerImpactResponseDto> response = restTemplate
                .withBasicAuth("admin", "AdminSecret123!")
                .getForEntity(
                        "/api/intelligence/predict/passenger-impact?flightNumber=6E-501&carrierCode=6E&originAirport=DEL&destAirport=BOM&disruptionProbability=0.39&expectedDelayMinutes=50.0",
                        PassengerImpactResponseDto.class
                );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        PassengerImpactResponseDto dto = response.getBody();
        assertNotNull(dto);
        assertEquals("6E-501", dto.getFlightNumber());
        assertEquals("6E", dto.getCarrierCode());
        assertEquals("DEL", dto.getOriginAirport());
        assertEquals("BOM", dto.getDestAirport());
        assertNotNull(dto.getSeatCapacity());
        assertTrue(dto.getSeatCapacity() > 0);
        assertNotNull(dto.getEstimatedPassengersTotal());
        assertNotNull(dto.getImpactSeverity());
        assertNotNull(dto.getCostExposureTier());
    }

    @Test
    @DisplayName("Stage 8: Risk Leaderboard endpoint returns HTTP 200")
    void testMonitoringLeaderboardEndpoint() {
        ResponseEntity<Map> response = restTemplate
                .withBasicAuth("admin", "AdminSecret123!")
                .getForEntity("/api/intelligence/monitoring/leaderboard", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("Stage 8: Airport Heatmap endpoint returns HTTP 200")
    void testMonitoringHeatmapEndpoint() {
        ResponseEntity<Map> response = restTemplate
                .withBasicAuth("admin", "AdminSecret123!")
                .getForEntity("/api/intelligence/monitoring/heatmap", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("Stage 8: PredictionServiceClient toggle allows Indian priority corridors")
    void testIndianPriorityCorridorToggle() {
        boolean original = predictionClient.isSupportIndianCorridors();
        try {
            // Default is false: Indian route returns UNSUPPORTED_AIRSPACE
            predictionClient.setSupportIndianCorridors(false);
            FlightPredictionRequestDto req = new FlightPredictionRequestDto(
                    "6E501", "6E", "DEL", "BOM", "08:00"
            );
            FlightPredictionResponseDto resp = predictionClient.predictDisruption(req);
            assertEquals("UNSUPPORTED_AIRSPACE", resp.getStatus());

            // When enabled, it recognizes DEL-BOM is a supported priority corridor
            predictionClient.setSupportIndianCorridors(true);
            // It attempts forward to FastAPI (if offline, falls back to MODEL_UNAVAILABLE instead of UNSUPPORTED_AIRSPACE)
            FlightPredictionResponseDto respEnabled = predictionClient.predictDisruption(req);
            assertNotEquals("UNSUPPORTED_AIRSPACE", respEnabled.getStatus());
        } finally {
            predictionClient.setSupportIndianCorridors(original);
        }
    }
}
