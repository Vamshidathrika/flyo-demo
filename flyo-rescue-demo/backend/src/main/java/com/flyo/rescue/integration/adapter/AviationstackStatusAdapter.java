package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.integration.FlightStatusProvider;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.dto.FlightStatusResponse;
import com.flyo.rescue.service.AviationRadarClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Component
public class AviationstackStatusAdapter implements FlightStatusProvider {

    private final AviationRadarClient aviationRadarClient;
    private final ProviderMetadata metadata;

    public AviationstackStatusAdapter(AviationRadarClient aviationRadarClient) {
        this.aviationRadarClient = aviationRadarClient;
        this.metadata = new ProviderMetadata(
                "aviationstack",
                "Aviationstack Global Flight Tracking API",
                ProviderCategory.FLIGHT_STATUS,
                ProviderCapabilityStatus.CONFIGURED_NOT_VERIFIED,
                List.of("REST", "JSON", "HTTPS"),
                450,
                "Commercial aggregator providing real-time flight telemetry, delays, and schedule states",
                false
        );
    }

    @Override
    public ProviderMetadata getMetadata() {
        return metadata;
    }

    @Override
    public FlightStatusResponse checkFlightStatus(String flightNumber, LocalDate date) {
        java.util.Optional<com.flyo.rescue.dto.FlightStatusAlert> alertOpt = aviationRadarClient.fetchLiveFlightStatus(flightNumber);
        if (alertOpt.isPresent()) {
            com.flyo.rescue.dto.FlightStatusAlert alert = alertOpt.get();
            return new FlightStatusResponse(
                    flightNumber,
                    alert.getStatus() != null ? alert.getStatus().toUpperCase() : "SCHEDULED",
                    alert.getDelayMinutes(),
                    Instant.now(),
                    Instant.now(),
                    "HYD",
                    "DEL",
                    alert.getSource() != null ? alert.getSource() : "aviationstack",
                    alert.getReason() != null ? alert.getReason() : "Disruption tracked via live radar feed"
            );
        } else {
            return new FlightStatusResponse(
                    flightNumber,
                    "STATUS_UNAVAILABLE",
                    0,
                    Instant.now(),
                    Instant.now(),
                    "---",
                    "---",
                    "aviationstack",
                    "No real-time radar telemetry received from provider for " + flightNumber
            );
        }
    }
}
