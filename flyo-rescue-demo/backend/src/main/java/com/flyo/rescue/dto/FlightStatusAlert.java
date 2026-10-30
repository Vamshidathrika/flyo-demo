package com.flyo.rescue.dto;

public class FlightStatusAlert {
    private String flight;
    private String status; // CANCELLED, DELAYED, ON_TIME
    private int delayMinutes;
    private String reason;
    private String source; // FlightAware, Cirium, AviationStack, AirlineDirect

    public FlightStatusAlert() {
    }

    public FlightStatusAlert(String flight, String status, int delayMinutes, String reason, String source) {
        this.flight = flight;
        this.status = status;
        this.delayMinutes = delayMinutes;
        this.reason = reason;
        this.source = source;
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
}
