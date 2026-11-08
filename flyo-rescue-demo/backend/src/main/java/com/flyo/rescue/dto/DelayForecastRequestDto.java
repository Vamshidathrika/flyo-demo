package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DelayForecastRequestDto {

    @JsonProperty("flight_number")
    private String flightNumber;

    @JsonProperty("carrier_code")
    private String carrierCode;

    @JsonProperty("origin_airport")
    private String originAirport;

    @JsonProperty("dest_airport")
    private String destAirport;

    @JsonProperty("scheduled_dep_time")
    private String scheduledDepTime;

    @JsonProperty("scheduled_arr_time")
    private String scheduledArrTime;

    @JsonProperty("departure_date")
    private String departureDate;

    @JsonProperty("operational_horizon")
    private String operationalHorizon = "T_MINUS_2H";

    @JsonProperty("temperature_c")
    private Double temperatureC;

    @JsonProperty("wind_speed_kmh")
    private Double windSpeedKmh;

    @JsonProperty("visibility_km")
    private Double visibilityKm;

    @JsonProperty("precipitation_mm")
    private Double precipitationMm;

    public DelayForecastRequestDto() {}

    public DelayForecastRequestDto(String flightNumber, String carrierCode, String originAirport,
                                   String destAirport, String scheduledDepTime) {
        this.flightNumber = flightNumber;
        this.carrierCode = carrierCode;
        this.originAirport = originAirport;
        this.destAirport = destAirport;
        this.scheduledDepTime = scheduledDepTime;
    }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getOriginAirport() { return originAirport; }
    public void setOriginAirport(String originAirport) { this.originAirport = originAirport; }

    public String getDestAirport() { return destAirport; }
    public void setDestAirport(String destAirport) { this.destAirport = destAirport; }

    public String getScheduledDepTime() { return scheduledDepTime; }
    public void setScheduledDepTime(String scheduledDepTime) { this.scheduledDepTime = scheduledDepTime; }

    public String getScheduledArrTime() { return scheduledArrTime; }
    public void setScheduledArrTime(String scheduledArrTime) { this.scheduledArrTime = scheduledArrTime; }

    public String getDepartureDate() { return departureDate; }
    public void setDepartureDate(String departureDate) { this.departureDate = departureDate; }

    public String getOperationalHorizon() { return operationalHorizon; }
    public void setOperationalHorizon(String operationalHorizon) { this.operationalHorizon = operationalHorizon; }

    public Double getTemperatureC() { return temperatureC; }
    public void setTemperatureC(Double temperatureC) { this.temperatureC = temperatureC; }

    public Double getWindSpeedKmh() { return windSpeedKmh; }
    public void setWindSpeedKmh(Double windSpeedKmh) { this.windSpeedKmh = windSpeedKmh; }

    public Double getVisibilityKm() { return visibilityKm; }
    public void setVisibilityKm(Double visibilityKm) { this.visibilityKm = visibilityKm; }

    public Double getPrecipitationMm() { return precipitationMm; }
    public void setPrecipitationMm(Double precipitationMm) { this.precipitationMm = precipitationMm; }
}
