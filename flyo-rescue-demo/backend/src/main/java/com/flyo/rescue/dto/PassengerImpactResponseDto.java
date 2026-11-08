package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PassengerImpactResponseDto {

    @JsonProperty("flight_number")
    private String flightNumber;

    @JsonProperty("carrier_code")
    private String carrierCode;

    @JsonProperty("origin_airport")
    private String originAirport;

    @JsonProperty("dest_airport")
    private String destAirport;

    @JsonProperty("aircraft_type")
    private String aircraftType;

    @JsonProperty("seat_capacity")
    private Integer seatCapacity;

    @JsonProperty("load_factor")
    private Double loadFactor;

    @JsonProperty("estimated_passengers_total")
    private Integer estimatedPassengersTotal;

    @JsonProperty("estimated_connecting_passengers")
    private Integer estimatedConnectingPassengers;

    @JsonProperty("estimated_point_to_point_passengers")
    private Integer estimatedPointToPointPassengers;

    @JsonProperty("estimated_elite_passengers")
    private Integer estimatedElitePassengers;

    @JsonProperty("vulnerable_connections_count")
    private Integer vulnerableConnectionsCount;

    @JsonProperty("projected_rebooking_demand")
    private Integer projectedRebookingDemand;

    @JsonProperty("estimated_rescue_workload_hours")
    private Double estimatedRescueWorkloadHours;

    @JsonProperty("impact_severity")
    private String impactSeverity;

    @JsonProperty("cost_exposure_tier")
    private String costExposureTier;

    @JsonProperty("disclaimer")
    private String disclaimer;

    public PassengerImpactResponseDto() {}

    public static PassengerImpactResponseDto fallback(String flightNumber, String carrierCode, String origin, String dest) {
        PassengerImpactResponseDto dto = new PassengerImpactResponseDto();
        dto.setFlightNumber(flightNumber);
        dto.setCarrierCode(carrierCode);
        dto.setOriginAirport(origin);
        dto.setDestAirport(dest);
        dto.setAircraftType("A320");
        dto.setSeatCapacity(180);
        dto.setLoadFactor(0.85);
        dto.setEstimatedPassengersTotal(153);
        dto.setEstimatedConnectingPassengers(45);
        dto.setEstimatedPointToPointPassengers(108);
        dto.setEstimatedElitePassengers(18);
        dto.setVulnerableConnectionsCount(12);
        dto.setProjectedRebookingDemand(25);
        dto.setEstimatedRescueWorkloadHours(4.2);
        dto.setImpactSeverity("MODERATE");
        dto.setCostExposureTier("TIER_2_MODERATE");
        dto.setDisclaimer("Impact estimation fallback. Human supervisor approval required.");
        return dto;
    }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getOriginAirport() { return originAirport; }
    public void setOriginAirport(String originAirport) { this.originAirport = originAirport; }

    public String getDestAirport() { return destAirport; }
    public void setDestAirport(String destAirport) { this.destAirport = destAirport; }

    public String getAircraftType() { return aircraftType; }
    public void setAircraftType(String aircraftType) { this.aircraftType = aircraftType; }

    public Integer getSeatCapacity() { return seatCapacity; }
    public void setSeatCapacity(Integer seatCapacity) { this.seatCapacity = seatCapacity; }

    public Double getLoadFactor() { return loadFactor; }
    public void setLoadFactor(Double loadFactor) { this.loadFactor = loadFactor; }

    public Integer getEstimatedPassengersTotal() { return estimatedPassengersTotal; }
    public void setEstimatedPassengersTotal(Integer estimatedPassengersTotal) { this.estimatedPassengersTotal = estimatedPassengersTotal; }

    public Integer getEstimatedConnectingPassengers() { return estimatedConnectingPassengers; }
    public void setEstimatedConnectingPassengers(Integer estimatedConnectingPassengers) { this.estimatedConnectingPassengers = estimatedConnectingPassengers; }

    public Integer getEstimatedPointToPointPassengers() { return estimatedPointToPointPassengers; }
    public void setEstimatedPointToPointPassengers(Integer estimatedPointToPointPassengers) { this.estimatedPointToPointPassengers = estimatedPointToPointPassengers; }

    public Integer getEstimatedElitePassengers() { return estimatedElitePassengers; }
    public void setEstimatedElitePassengers(Integer estimatedElitePassengers) { this.estimatedElitePassengers = estimatedElitePassengers; }

    public Integer getVulnerableConnectionsCount() { return vulnerableConnectionsCount; }
    public void setVulnerableConnectionsCount(Integer vulnerableConnectionsCount) { this.vulnerableConnectionsCount = vulnerableConnectionsCount; }

    public Integer getProjectedRebookingDemand() { return projectedRebookingDemand; }
    public void setProjectedRebookingDemand(Integer projectedRebookingDemand) { this.projectedRebookingDemand = projectedRebookingDemand; }

    public Double getEstimatedRescueWorkloadHours() { return estimatedRescueWorkloadHours; }
    public void setEstimatedRescueWorkloadHours(Double estimatedRescueWorkloadHours) { this.estimatedRescueWorkloadHours = estimatedRescueWorkloadHours; }

    public String getImpactSeverity() { return impactSeverity; }
    public void setImpactSeverity(String impactSeverity) { this.impactSeverity = impactSeverity; }

    public String getCostExposureTier() { return costExposureTier; }
    public void setCostExposureTier(String costExposureTier) { this.costExposureTier = costExposureTier; }

    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String disclaimer) { this.disclaimer = disclaimer; }
}
