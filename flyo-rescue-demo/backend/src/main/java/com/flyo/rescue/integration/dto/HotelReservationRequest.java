package com.flyo.rescue.integration.dto;

public class HotelReservationRequest {
    private String caseId;
    private String passengerName;
    private String airportCity; // e.g. "DEL"
    private String checkInDate;
    private String checkOutDate;
    private int maxBudget;

    public HotelReservationRequest() {}

    public HotelReservationRequest(String caseId, String passengerName, String airportCity, String checkInDate, String checkOutDate, int maxBudget) {
        this.caseId = caseId;
        this.passengerName = passengerName;
        this.airportCity = airportCity;
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.maxBudget = maxBudget;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public String getAirportCity() { return airportCity; }
    public void setAirportCity(String airportCity) { this.airportCity = airportCity; }
    public String getCheckInDate() { return checkInDate; }
    public void setCheckInDate(String checkInDate) { this.checkInDate = checkInDate; }
    public String getCheckOutDate() { return checkOutDate; }
    public void setCheckOutDate(String checkOutDate) { this.checkOutDate = checkOutDate; }
    public int getMaxBudget() { return maxBudget; }
    public void setMaxBudget(int maxBudget) { this.maxBudget = maxBudget; }
}
