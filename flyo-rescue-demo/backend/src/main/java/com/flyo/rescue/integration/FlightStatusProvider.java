package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.FlightStatusResponse;

import java.time.LocalDate;

public interface FlightStatusProvider {
    ProviderMetadata getMetadata();
    FlightStatusResponse checkFlightStatus(String flightNumber, LocalDate date);
}
