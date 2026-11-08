package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PassengerImpactRequestDto {

    @JsonProperty("flight_number")
    private String flightNumber;

    @JsonProperty("carrier_code")
    private String carrierCode;

    @JsonProperty("origin_airport")
    private String originAirport;

    @JsonProperty("dest_airport")
    private String destAirport;

    @JsonProperty("disruption_probability")
    private Double disruptionProbability;

    @JsonProperty("expected_delay_minutes")
    private Double expectedDelayMinutes;

    @JsonProperty("is_cancelled")
    private Boolean isCancelled = false;

    @JsonProperty("aircraft_type")
    private String aircraftType = "A320";

    @JsonProperty("load_factor")
    private Double loadFactor = 0.85;

    @JsonProperty("connecting_passenger_ratio")
    private Double connectingPassengerRatio;

    @JsonProperty("elite_passenger_ratio")
    private Double elitePassengerRatio = 0.12;

    public PassengerImpactRequestDto() {}

    public PassengerImpactRequestDto(String flightNumber, String carrierCode, String originAirport,
                                     String destAirport, Double disruptionProbability, Double expectedDelayMinutes) {
        this.flightNumber = flightNumber;
        this.carrierCode = carrierCode;
        this.originAirport = originAirport;
        this.destAirport = destAirport;
        this.disruptionProbability = disruptionProbability;
        this.expectedDelayMinutes = expectedDelayMinutes;
    }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getOriginAirport() { return originAirport; }
    public void setOriginAirport(String originAirport) { this.originAirport = originAirport; }

    public String getDestAirport() { return destAirport; }
    public void setDestAirport(String destAirport) { this.destAirport = destAirport; }

    public Double getDisruptionProbability() { return disruptionProbability; }
    public void setDisruptionProbability(Double disruptionProbability) { this.disruptionProbability = disruptionProbability; }

    public Double getExpectedDelayMinutes() { return expectedDelayMinutes; }
    public void setExpectedDelayMinutes(Double expectedDelayMinutes) { this.expectedDelayMinutes = expectedDelayMinutes; }

    public Boolean getIsCancelled() { return isCancelled; }
    public void setIsCancelled(Boolean isCancelled) { this.isCancelled = isCancelled; }

    public String getAircraftType() { return aircraftType; }
    public void setAircraftType(String aircraftType) { this.aircraftType = aircraftType; }

    public Double getLoadFactor() { return loadFactor; }
    public void setLoadFactor(Double loadFactor) { this.loadFactor = loadFactor; }

    public Double getConnectingPassengerRatio() { return connectingPassengerRatio; }
    public void setConnectingPassengerRatio(Double connectingPassengerRatio) { this.connectingPassengerRatio = connectingPassengerRatio; }

    public Double getElitePassengerRatio() { return elitePassengerRatio; }
    public void setElitePassengerRatio(Double elitePassengerRatio) { this.elitePassengerRatio = elitePassengerRatio; }
}
