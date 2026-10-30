package com.flyo.rescue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    private String id;
    private String passengerName;
    private String pnr;
    private String route;
    private String flight;
    private String dateTime;
    private String onwardFlight;
    private String hotelNote;
    private String agency;
    private String status;

    public Booking() {
    }

    public Booking(String id, String passengerName, String pnr, String route, String flight,
                   String dateTime, String onwardFlight, String hotelNote, String agency, String status) {
        this.id = id;
        this.passengerName = passengerName;
        this.pnr = pnr;
        this.route = route;
        this.flight = flight;
        this.dateTime = dateTime;
        this.onwardFlight = onwardFlight;
        this.hotelNote = hotelNote;
        this.agency = agency;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    public String getPnr() {
        return pnr;
    }

    public void setPnr(String pnr) {
        this.pnr = pnr;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getFlight() {
        return flight;
    }

    public void setFlight(String flight) {
        this.flight = flight;
    }

    public String getDateTime() {
        return dateTime;
    }

    public void setDateTime(String dateTime) {
        this.dateTime = dateTime;
    }

    public String getOnwardFlight() {
        return onwardFlight;
    }

    public void setOnwardFlight(String onwardFlight) {
        this.onwardFlight = onwardFlight;
    }

    public String getHotelNote() {
        return hotelNote;
    }

    public void setHotelNote(String hotelNote) {
        this.hotelNote = hotelNote;
    }

    public String getAgency() {
        return agency;
    }

    public void setAgency(String agency) {
        this.agency = agency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
