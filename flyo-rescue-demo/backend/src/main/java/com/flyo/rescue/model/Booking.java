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
    private String agency = "Flyo Corporate Travel";
    private String status;
    private String passengerPhone = "+919876543210";
    private String tenantId = "tenant_default";
    private String passengerId;
    private String itineraryId;
    private Long itineraryVersion = 1L;

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
        this.passengerPhone = "+919876543210";
    }

    public Booking(String id, String passengerName, String pnr, String route, String flight,
                   String dateTime, String onwardFlight, String hotelNote, String agency, String status, String passengerPhone) {
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
        this.passengerPhone = passengerPhone;
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

    public String getPassengerPhone() {
        return passengerPhone != null && !passengerPhone.isBlank() ? passengerPhone : "+919876543210";
    }

    public void setPassengerPhone(String passengerPhone) {
        this.passengerPhone = passengerPhone;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getPassengerId() {
        if (passengerId == null || passengerId.isBlank()) {
            return "PAX-" + (pnr != null ? pnr : (id != null ? id : "DEFAULT"));
        }
        return passengerId;
    }

    public void setPassengerId(String passengerId) {
        this.passengerId = passengerId;
    }

    public String getItineraryId() {
        if (itineraryId == null || itineraryId.isBlank()) {
            return "ITIN-" + (pnr != null ? pnr : (id != null ? id : "DEFAULT"));
        }
        return itineraryId;
    }

    public void setItineraryId(String itineraryId) {
        this.itineraryId = itineraryId;
    }

    public Long getItineraryVersion() {
        return itineraryVersion != null ? itineraryVersion : 1L;
    }

    public void setItineraryVersion(Long itineraryVersion) {
        this.itineraryVersion = itineraryVersion;
    }

    public String getOriginAirport() {
        if (route == null || route.isBlank()) return "HYD";
        String r = route.replaceAll("\\s*(?:→|->|to|-)\\s*", "→");
        String[] parts = r.split("→");
        return parts.length > 0 ? parts[0].trim().toUpperCase() : "HYD";
    }

    public String getDestinationAirport() {
        if (route == null || route.isBlank()) return "DEL";
        String r = route.replaceAll("\\s*(?:→|->|to|-)\\s*", "→");
        String[] parts = r.split("→");
        return parts.length > 1 ? parts[1].trim().toUpperCase() : "DEL";
    }

    public boolean isMultiSegment() {
        return onwardFlight != null && !onwardFlight.isBlank() && !onwardFlight.equalsIgnoreCase("None");
    }

    public String getFinalDestination() {
        if (!isMultiSegment()) {
            return getDestinationAirport();
        }
        // Extract destination from onward flight if present, e.g. "DEL → Toronto (YYZ) Tomorrow 09:30" or "HYD → DEL"
        String onward = onwardFlight;
        if (onward.contains("(") && onward.contains(")")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([A-Z]{3})\\)").matcher(onward);
            if (m.find()) {
                return m.group(1).toUpperCase();
            }
        }
        if (onward.contains("→") || onward.contains("->") || onward.contains(" to ")) {
            String r = onward.replaceAll("\\s*(?:→|->|to)\\s*", "→");
            String[] parts = r.split("→");
            if (parts.length > 1) {
                String destPart = parts[parts.length - 1].trim();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([A-Z]{3})\\b").matcher(destPart);
                if (m.find()) {
                    return m.group(1).toUpperCase();
                }
            }
        }
        return getDestinationAirport();
    }
}
