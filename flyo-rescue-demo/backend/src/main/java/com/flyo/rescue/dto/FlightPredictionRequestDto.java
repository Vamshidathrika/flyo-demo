package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FlightPredictionRequestDto {

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

    @JsonProperty("day_of_week")
    private Integer dayOfWeek;

    @JsonProperty("day_of_month")
    private Integer dayOfMonth;

    @JsonProperty("month")
    private Integer month;

    @JsonProperty("distance_miles")
    private Double distanceMiles;

    @JsonProperty("scheduled_elapsed_minutes")
    private Double scheduledElapsedMinutes;

    public FlightPredictionRequestDto() {}

    public FlightPredictionRequestDto(String flightNumber, String carrierCode, String originAirport,
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

    public Integer getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Integer dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public Integer getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }

    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }

    public Double getDistanceMiles() { return distanceMiles; }
    public void setDistanceMiles(Double distanceMiles) { this.distanceMiles = distanceMiles; }

    public Double getScheduledElapsedMinutes() { return scheduledElapsedMinutes; }
    public void setScheduledElapsedMinutes(Double scheduledElapsedMinutes) { this.scheduledElapsedMinutes = scheduledElapsedMinutes; }
}
