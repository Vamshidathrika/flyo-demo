package com.flyo.rescue.dto;

public class FlightStatusAlert {
    private String flight;
    private String status; // CANCELLED, DELAYED, ON_TIME
    private int delayMinutes;
    private String reason;
    private String source; // FlightAware, Cirium, AviationStack, AirlineDirect
    private String originAirport;
    private String destinationAirport;
    private String scheduledDeparture;
    private String bookingId;
    private String segmentId;

    public FlightStatusAlert() {
    }

    public FlightStatusAlert(String flight, String status, int delayMinutes, String reason, String source) {
        this.flight = flight;
        this.status = status;
        this.delayMinutes = delayMinutes;
        this.reason = reason;
        this.source = source;
    }

    public FlightStatusAlert(String flight, String status, int delayMinutes, String reason, String source,
                             String originAirport, String destinationAirport) {
        this.flight = flight;
        this.status = status;
        this.delayMinutes = delayMinutes;
        this.reason = reason;
        this.source = source;
        this.originAirport = originAirport;
        this.destinationAirport = destinationAirport;
    }

    public String getFlight() {
        return flight;
    }

    public void setFlight(String flight) {
        this.flight = flight;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getDelayMinutes() {
        return delayMinutes;
    }

    public void setDelayMinutes(int delayMinutes) {
        this.delayMinutes = delayMinutes;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    public String getScheduledDeparture() {
        return scheduledDeparture;
    }

    public void setScheduledDeparture(String scheduledDeparture) {
        this.scheduledDeparture = scheduledDeparture;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }
}
