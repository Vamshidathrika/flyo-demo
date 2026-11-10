package com.flyo.rescue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "flight_segments")
public class FlightSegment {

    @Id
    private String id;
    private String bookingId;
    private String passengerId;
    private String pnr;
    private String itineraryId;
    private int segmentIndex;
    private String flightNumber;
    private String originAirport;
    private String destinationAirport;
    private String scheduledDeparture;
    private String scheduledArrival;
    private String status = "CONFIRMED";
    private Long itineraryVersion = 1L;
    private String tenantId = "tenant_default";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    public FlightSegment() {
    }

    public FlightSegment(String id, String bookingId, String passengerId, String pnr,
                         String itineraryId, int segmentIndex, String flightNumber,
                         String originAirport, String destinationAirport,
                         String scheduledDeparture, String scheduledArrival) {
        this.id = id;
        this.bookingId = bookingId;
        this.passengerId = passengerId;
        this.pnr = pnr;
        this.itineraryId = itineraryId;
        this.segmentIndex = segmentIndex;
        this.flightNumber = flightNumber;
        this.originAirport = originAirport;
        this.destinationAirport = destinationAirport;
        this.scheduledDeparture = scheduledDeparture;
        this.scheduledArrival = scheduledArrival;
        this.status = "CONFIRMED";
        this.itineraryVersion = 1L;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(String passengerId) {
        this.passengerId = passengerId;
    }

    public String getPnr() {
        return pnr;
    }

    public void setPnr(String pnr) {
        this.pnr = pnr;
    }

    public String getItineraryId() {
        return itineraryId;
    }

    public void setItineraryId(String itineraryId) {
        this.itineraryId = itineraryId;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
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

    public String getScheduledArrival() {
        return scheduledArrival;
    }

    public void setScheduledArrival(String scheduledArrival) {
        this.scheduledArrival = scheduledArrival;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getItineraryVersion() {
        return itineraryVersion;
    }

    public void setItineraryVersion(Long itineraryVersion) {
        this.itineraryVersion = itineraryVersion;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getRoute() {
        return originAirport + " → " + destinationAirport;
    }
}
