package com.flyo.rescue.integration.dto;

import java.time.Instant;
import java.util.List;

public class FlightStatusResponse {
    private String flightNumber;
    private String status; // ON_TIME, DELAYED, CANCELLED, DIVERTED
    private int delayMinutes;
    private Instant scheduledDeparture;
    private Instant estimatedDeparture;
    private String origin;
    private String destination;
    private String sourceProvider;
    private String rawDisruptionReason;

    public FlightStatusResponse() {}

    public FlightStatusResponse(String flightNumber, String status, int delayMinutes,
                                Instant scheduledDeparture, Instant estimatedDeparture,
                                String origin, String destination, String sourceProvider,
                                String rawDisruptionReason) {
        this.flightNumber = flightNumber;
        this.status = status;
        this.delayMinutes = delayMinutes;
        this.scheduledDeparture = scheduledDeparture;
        this.estimatedDeparture = estimatedDeparture;
        this.origin = origin;
        this.destination = destination;
        this.sourceProvider = sourceProvider;
        this.rawDisruptionReason = rawDisruptionReason;
    }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getDelayMinutes() { return delayMinutes; }
    public void setDelayMinutes(int delayMinutes) { this.delayMinutes = delayMinutes; }
    public Instant getScheduledDeparture() { return scheduledDeparture; }
    public void setScheduledDeparture(Instant scheduledDeparture) { this.scheduledDeparture = scheduledDeparture; }
    public Instant getEstimatedDeparture() { return estimatedDeparture; }
    public void setEstimatedDeparture(Instant estimatedDeparture) { this.estimatedDeparture = estimatedDeparture; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getSourceProvider() { return sourceProvider; }
    public void setSourceProvider(String sourceProvider) { this.sourceProvider = sourceProvider; }
    public String getRawDisruptionReason() { return rawDisruptionReason; }
    public void setRawDisruptionReason(String rawDisruptionReason) { this.rawDisruptionReason = rawDisruptionReason; }
}
