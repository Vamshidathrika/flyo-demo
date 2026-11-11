package com.flyo.rescue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rebook_options")
public class RebookOption {

    @Id
    private String id;
    private String caseId;
    private String flight;
    private String route;
    private String departure = "20:00";
    private String arrival = "22:00";
    private int connectionMinutes;
    private int extraCost;
    private boolean savesConnection;
    private String reason;
    private int rank;
    private Integer riskScore = 15;
    private String riskLevel = "LOW";
    private String riskFactors;

    private String provider = "StarSky Mock Airline Engine";
    private String providerOfferId;
    private String flightSegments;
    private java.time.LocalDateTime departureTimestamp;
    private java.time.LocalDateTime arrivalTimestamp;
    private String departureTimeZone = "Asia/Kolkata";
    private String arrivalTimeZone = "Asia/Kolkata";
    private String connectionDetails;
    private String currency = "INR";
    private java.time.LocalDateTime fareExpiry;
    private String baggageConditions = "Standard carrier allowance; separate-ticket connections require airport re-check";
    private String fareRules = "Involuntary disruption rebook rules apply per DGCA CAR / Rule 240";
    private String onwardItinerary;
    private java.time.LocalDateTime calculationTimestamp = java.time.LocalDateTime.now();
    private String rescueAttemptId;
    private String tenantId = "tenant_default";
    private String bookingId;
    private String incidentId;
    private String sourceSegmentId;
    private Long itineraryVersion = 1L;
    private String originAirport;
    private String destinationAirport;
    private String finalDestination;
    private String deterministicCacheKey;
    private String recoveryStrategy = "REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD";
    private boolean preservesOnwardConnection = true;
    private boolean reroutesEntireJourney = false;
    private boolean baggageThroughChecked = false;

    public RebookOption() {
    }

    public RebookOption(String id, String caseId, String flight, String route, String departure,
                        String arrival, int connectionMinutes, int extraCost,
                        boolean savesConnection, String reason, int rank) {
        this.id = id;
        this.caseId = caseId;
        this.flight = flight;
        this.route = route;
        this.departure = departure;
        this.arrival = arrival;
        this.connectionMinutes = connectionMinutes;
        this.extraCost = extraCost;
        this.savesConnection = savesConnection;
        this.reason = reason;
        this.rank = rank;
    }

    public RebookOption(String id, String caseId, String flight, String route, String departure,
                        String arrival, int connectionMinutes, int extraCost,
                        int rank, boolean savesConnection, String reason) {
        this(id, caseId, flight, route, departure, arrival, connectionMinutes, extraCost, savesConnection, reason, rank);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getFlight() {
        return flight;
    }

    public void setFlight(String flight) {
        this.flight = flight;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getDeparture() {
        return departure;
    }

    public void setDeparture(String departure) {
        this.departure = departure;
    }

    public String getArrival() {
        return arrival;
    }

    public void setArrival(String arrival) {
        this.arrival = arrival;
    }

    public int getConnectionMinutes() {
        return connectionMinutes;
    }

    public void setConnectionMinutes(int connectionMinutes) {
        this.connectionMinutes = connectionMinutes;
    }

    public int getExtraCost() {
        return extraCost;
    }

    public void setExtraCost(int extraCost) {
        this.extraCost = extraCost;
    }

    public boolean isSavesConnection() {
        return savesConnection;
    }

    public void setSavesConnection(boolean savesConnection) {
        this.savesConnection = savesConnection;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getRiskFactors() {
        return riskFactors;
    }

    public void setRiskFactors(String riskFactors) {
        this.riskFactors = riskFactors;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderOfferId() {
        return providerOfferId;
    }

    public void setProviderOfferId(String providerOfferId) {
        this.providerOfferId = providerOfferId;
    }

    public String getFlightSegments() {
        return flightSegments;
    }

    public void setFlightSegments(String flightSegments) {
        this.flightSegments = flightSegments;
    }

    public java.time.LocalDateTime getDepartureTimestamp() {
        return departureTimestamp;
    }

    public void setDepartureTimestamp(java.time.LocalDateTime departureTimestamp) {
        this.departureTimestamp = departureTimestamp;
    }

    public java.time.LocalDateTime getArrivalTimestamp() {
        return arrivalTimestamp;
    }

    public void setArrivalTimestamp(java.time.LocalDateTime arrivalTimestamp) {
        this.arrivalTimestamp = arrivalTimestamp;
    }

    public String getDepartureTimeZone() {
        return departureTimeZone;
    }

    public void setDepartureTimeZone(String departureTimeZone) {
        this.departureTimeZone = departureTimeZone;
    }

    public String getArrivalTimeZone() {
        return arrivalTimeZone;
    }

    public void setArrivalTimeZone(String arrivalTimeZone) {
        this.arrivalTimeZone = arrivalTimeZone;
    }

    public String getConnectionDetails() {
        return connectionDetails;
    }

    public void setConnectionDetails(String connectionDetails) {
        this.connectionDetails = connectionDetails;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public java.time.LocalDateTime getFareExpiry() {
        return fareExpiry;
    }

    public void setFareExpiry(java.time.LocalDateTime fareExpiry) {
        this.fareExpiry = fareExpiry;
    }

    public String getBaggageConditions() {
        return baggageConditions;
    }

    public void setBaggageConditions(String baggageConditions) {
        this.baggageConditions = baggageConditions;
    }

    public String getFareRules() {
        return fareRules;
    }

    public void setFareRules(String fareRules) {
        this.fareRules = fareRules;
    }

    public String getOnwardItinerary() {
        return onwardItinerary;
    }

    public void setOnwardItinerary(String onwardItinerary) {
        this.onwardItinerary = onwardItinerary;
    }

    public java.time.LocalDateTime getCalculationTimestamp() {
        return calculationTimestamp;
    }

    public void setCalculationTimestamp(java.time.LocalDateTime calculationTimestamp) {
        this.calculationTimestamp = calculationTimestamp;
    }

    public String getRescueAttemptId() {
        return rescueAttemptId;
    }

    public void setRescueAttemptId(String rescueAttemptId) {
        this.rescueAttemptId = rescueAttemptId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getIncidentId() {
        return incidentId != null ? incidentId : caseId;
    }

    public void setIncidentId(String incidentId) {
        this.incidentId = incidentId;
        if (this.caseId == null) this.caseId = incidentId;
    }

    public String getSourceSegmentId() {
        return sourceSegmentId;
    }

    public void setSourceSegmentId(String sourceSegmentId) {
        this.sourceSegmentId = sourceSegmentId;
    }

    public Long getItineraryVersion() {
        return itineraryVersion != null ? itineraryVersion : 1L;
    }

    public void setItineraryVersion(Long itineraryVersion) {
        this.itineraryVersion = itineraryVersion;
    }

    public String getOriginAirport() {
        return originAirport;
    }

    public void setOriginAirport(String originAirport) {
        this.originAirport = originAirport;
    }

    public String getDestinationAirport() {
        return destinationAirport;
    }

    public void setDestinationAirport(String destinationAirport) {
        this.destinationAirport = destinationAirport;
    }

    public String getFinalDestination() {
        return finalDestination;
    }

    public void setFinalDestination(String finalDestination) {
        this.finalDestination = finalDestination;
    }

    public String getDeterministicCacheKey() {
        return deterministicCacheKey;
    }

    public void setDeterministicCacheKey(String deterministicCacheKey) {
        this.deterministicCacheKey = deterministicCacheKey;
    }

    public String getRecoveryStrategy() {
        return recoveryStrategy != null ? recoveryStrategy : "REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD";
    }

    public void setRecoveryStrategy(String recoveryStrategy) {
        this.recoveryStrategy = recoveryStrategy;
    }

    public boolean isPreservesOnwardConnection() {
        return preservesOnwardConnection;
    }

    public void setPreservesOnwardConnection(boolean preservesOnwardConnection) {
        this.preservesOnwardConnection = preservesOnwardConnection;
    }

    public boolean isReroutesEntireJourney() {
        return reroutesEntireJourney;
    }

    public void setReroutesEntireJourney(boolean reroutesEntireJourney) {
        this.reroutesEntireJourney = reroutesEntireJourney;
    }

    public boolean isBaggageThroughChecked() {
        return baggageThroughChecked;
    }

    public void setBaggageThroughChecked(boolean baggageThroughChecked) {
        this.baggageThroughChecked = baggageThroughChecked;
    }
}
