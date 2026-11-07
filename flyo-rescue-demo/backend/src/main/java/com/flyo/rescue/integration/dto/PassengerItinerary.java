package com.flyo.rescue.integration.dto;

import java.util.List;

public class PassengerItinerary {
    private String bookingReference;
    private String passengerName;
    private List<PnrRecord> segments;
    private String agency;
    private String status;

    public PassengerItinerary() {}

    public PassengerItinerary(String bookingReference, String passengerName, List<PnrRecord> segments, String agency, String status) {
        this.bookingReference = bookingReference;
        this.passengerName = passengerName;
        this.segments = segments;
        this.agency = agency;
        this.status = status;
    }

    public String getBookingReference() { return bookingReference; }
    public void setBookingReference(String bookingReference) { this.bookingReference = bookingReference; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public List<PnrRecord> getSegments() { return segments; }
    public void setSegments(List<PnrRecord> segments) { this.segments = segments; }
    public String getAgency() { return agency; }
    public void setAgency(String agency) { this.agency = agency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
