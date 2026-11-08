package com.flyo.rescue.integration.dto;

import java.util.List;

public class PnrRecord {
    private String pnr;
    private String passengerName;
    private String passengerPhone;
    private String passengerEmail;
    private String currentFlight;
    private String route;
    private String onwardFlight;
    private String ticketNumber;
    private String couponStatus; // OPEN, CHECKED_IN, FLOWN, VOID, EXCHANGED
    private String bookingStatus;

    public PnrRecord() {}

    public PnrRecord(String pnr, String passengerName, String passengerPhone, String passengerEmail,
                     String currentFlight, String route, String onwardFlight, String ticketNumber,
                     String couponStatus, String bookingStatus) {
        this.pnr = pnr;
        this.passengerName = passengerName;
        this.passengerPhone = passengerPhone;
        this.passengerEmail = passengerEmail;
        this.currentFlight = currentFlight;
        this.route = route;
        this.onwardFlight = onwardFlight;
        this.ticketNumber = ticketNumber;
        this.couponStatus = couponStatus;
        this.bookingStatus = bookingStatus;
    }

    public String getPnr() { return pnr; }
    public void setPnr(String pnr) { this.pnr = pnr; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public String getPassengerPhone() { return passengerPhone; }
    public void setPassengerPhone(String passengerPhone) { this.passengerPhone = passengerPhone; }
    public String getPassengerEmail() { return passengerEmail; }
    public void setPassengerEmail(String passengerEmail) { this.passengerEmail = passengerEmail; }
    public String getCurrentFlight() { return currentFlight; }
    public void setCurrentFlight(String currentFlight) { this.currentFlight = currentFlight; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getOnwardFlight() { return onwardFlight; }
    public void setOnwardFlight(String onwardFlight) { this.onwardFlight = onwardFlight; }
    public String getTicketNumber() { return ticketNumber; }
    public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }
    public String getCouponStatus() { return couponStatus; }
    public void setCouponStatus(String couponStatus) { this.couponStatus = couponStatus; }
    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }
}
