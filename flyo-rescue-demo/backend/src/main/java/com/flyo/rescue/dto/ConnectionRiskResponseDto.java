package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionRiskResponseDto {

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

    @JsonProperty("effective_mct_minutes")
    private Integer effectiveMctMinutes;

    @JsonProperty("is_inter_terminal_transfer")
    private Boolean isInterTerminalTransfer;

    @JsonProperty("available_window_minutes")
    private Double availableWindowMinutes;

    @JsonProperty("connection_margin_minutes")
    private Double connectionMarginMinutes;

    @JsonProperty("connection_failure_probability")
    private Double connectionFailureProbability;

    @JsonProperty("risk_tier")
    private String riskTier;

    @JsonProperty("recommended_action")
    private String recommendedAction;

    @JsonProperty("action_description")
    private String actionDescription;

    @JsonProperty("passenger_count")
    private Integer passengerCount;

    @JsonProperty("timestamp_utc")
    private String timestampUtc;

    public ConnectionRiskResponseDto() {}

    public static ConnectionRiskResponseDto fallback(String airport, String inbound, String outbound) {
        ConnectionRiskResponseDto dto = new ConnectionRiskResponseDto();
        dto.setAirportCode(airport);
        dto.setInboundFlightNumber(inbound);
        dto.setOutboundFlightNumber(outbound);
        dto.setScheduledConnectionMinutes(60);
        dto.setPredictedInboundDelayMinutes(0.0);
        dto.setEffectiveMctMinutes(45);
        dto.setIsInterTerminalTransfer(false);
        dto.setAvailableWindowMinutes(60.0);
        dto.setConnectionMarginMinutes(15.0);
        dto.setConnectionFailureProbability(0.05);
        dto.setRiskTier("LOW");
        dto.setRecommendedAction("STANDARD_MONITORING");
        dto.setActionDescription("Connection service unavailable. Default fallback applied.");
        dto.setPassengerCount(1);
        return dto;
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

    public Integer getEffectiveMctMinutes() { return effectiveMctMinutes; }
    public void setEffectiveMctMinutes(Integer effectiveMctMinutes) { this.effectiveMctMinutes = effectiveMctMinutes; }

    public Boolean getIsInterTerminalTransfer() { return isInterTerminalTransfer; }
    public void setIsInterTerminalTransfer(Boolean interTerminalTransfer) { isInterTerminalTransfer = interTerminalTransfer; }

    public Double getAvailableWindowMinutes() { return availableWindowMinutes; }
    public void setAvailableWindowMinutes(Double availableWindowMinutes) { this.availableWindowMinutes = availableWindowMinutes; }

    public Double getConnectionMarginMinutes() { return connectionMarginMinutes; }
    public void setConnectionMarginMinutes(Double connectionMarginMinutes) { this.connectionMarginMinutes = connectionMarginMinutes; }

    public Double getConnectionFailureProbability() { return connectionFailureProbability; }
    public void setConnectionFailureProbability(Double connectionFailureProbability) { this.connectionFailureProbability = connectionFailureProbability; }

    public String getRiskTier() { return riskTier; }
    public void setRiskTier(String riskTier) { this.riskTier = riskTier; }

    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }

    public String getActionDescription() { return actionDescription; }
    public void setActionDescription(String actionDescription) { this.actionDescription = actionDescription; }

    public Integer getPassengerCount() { return passengerCount; }
    public void setPassengerCount(Integer passengerCount) { this.passengerCount = passengerCount; }

    public String getTimestampUtc() { return timestampUtc; }
    public void setTimestampUtc(String timestampUtc) { this.timestampUtc = timestampUtc; }
}
