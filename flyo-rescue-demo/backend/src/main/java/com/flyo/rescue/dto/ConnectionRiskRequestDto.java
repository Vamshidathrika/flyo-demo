package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionRiskRequestDto {

    @JsonProperty("airport_code")
    private String airportCode;

    @JsonProperty("inbound_flight_number")
    private String inboundFlightNumber;

    @JsonProperty("outbound_flight_number")
    private String outboundFlightNumber;

    @JsonProperty("scheduled_connection_minutes")
    private Integer scheduledConnectionMinutes;

    @JsonProperty("predicted_inbound_delay_minutes")
    private Double predictedInboundDelayMinutes;

    @JsonProperty("inbound_terminal")
    private String inboundTerminal;

    @JsonProperty("outbound_terminal")
    private String outboundTerminal;

    @JsonProperty("is_international")
    private Boolean isInternational = false;

    @JsonProperty("has_checked_baggage")
    private Boolean hasCheckedBaggage = true;

    @JsonProperty("passenger_count")
    private Integer passengerCount = 1;

    public ConnectionRiskRequestDto() {}

    public ConnectionRiskRequestDto(String airportCode, String inboundFlightNumber,
                                    String outboundFlightNumber, Integer scheduledConnectionMinutes,
                                    Double predictedInboundDelayMinutes) {
        this.airportCode = airportCode;
        this.inboundFlightNumber = inboundFlightNumber;
        this.outboundFlightNumber = outboundFlightNumber;
        this.scheduledConnectionMinutes = scheduledConnectionMinutes;
        this.predictedInboundDelayMinutes = predictedInboundDelayMinutes;
    }

    public String getAirportCode() { return airportCode; }
    public void setAirportCode(String airportCode) { this.airportCode = airportCode; }

    public String getInboundFlightNumber() { return inboundFlightNumber; }
    public void setInboundFlightNumber(String inboundFlightNumber) { this.inboundFlightNumber = inboundFlightNumber; }

    public String getOutboundFlightNumber() { return outboundFlightNumber; }
    public void setOutboundFlightNumber(String outboundFlightNumber) { this.outboundFlightNumber = outboundFlightNumber; }

    public Integer getScheduledConnectionMinutes() { return scheduledConnectionMinutes; }
    public void setScheduledConnectionMinutes(Integer scheduledConnectionMinutes) { this.scheduledConnectionMinutes = scheduledConnectionMinutes; }

    public Double getPredictedInboundDelayMinutes() { return predictedInboundDelayMinutes; }
    public void setPredictedInboundDelayMinutes(Double predictedInboundDelayMinutes) { this.predictedInboundDelayMinutes = predictedInboundDelayMinutes; }

    public String getInboundTerminal() { return inboundTerminal; }
    public void setInboundTerminal(String inboundTerminal) { this.inboundTerminal = inboundTerminal; }

    public String getOutboundTerminal() { return outboundTerminal; }
    public void setOutboundTerminal(String outboundTerminal) { this.outboundTerminal = outboundTerminal; }

    public Boolean getIsInternational() { return isInternational; }
    public void setIsInternational(Boolean isInternational) { this.isInternational = isInternational; }

    public Boolean getHasCheckedBaggage() { return hasCheckedBaggage; }
    public void setHasCheckedBaggage(Boolean hasCheckedBaggage) { this.hasCheckedBaggage = hasCheckedBaggage; }

    public Integer getPassengerCount() { return passengerCount; }
    public void setPassengerCount(Integer passengerCount) { this.passengerCount = passengerCount; }
}
