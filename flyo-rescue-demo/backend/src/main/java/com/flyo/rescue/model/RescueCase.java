package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "rescue_cases")
public class RescueCase {

    @Id
    private String id;
    private String bookingId;
    private String passengerName = "Passenger";
    private String state; // DETECTED, OPTIONS_READY, AWAITING_APPROVAL, REBOOKING, DONE, FAILED_ESCALATED, ESCALATED
    
    @Column(length = 2000)
    private String cancelledSegment;
    
    private String flight;
    
    @Column(length = 2000)
    private String impact;
    
    private String missedConnection;
    private String hotelRisk;
    private String approvedOptionId;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String newPnr;
    
    @Column(length = 2000)
    private String error;
    
    @Column(length = 2000)
    private String manualNextStep;
    
    private String passengerPhone;
    private String passengerDecision;
    private String selectedOptionId;
    private Integer passengerConfirmedAmount;
    private String passengerConfirmedCurrency;
    private LocalDateTime passengerOtpVerifiedAt;

    private Integer reconciledConfidence = 95;

    @Column(length = 2000)
    private String disruptionEvidence;

    @Column(length = 1000)
    private String weatherSummary;

    private String tenantId = "tenant_default";
    
    private String correlationId;
    private String ticketNumber;
    private String ticketStatus = "UNVERIFIED";
    private String notificationStatus = "PENDING";
    private String crmSyncStatus = "PENDING";

    private String rescueAttemptId;
    private String providerStatus = "SIMULATED";
    private String providerOrderId;
    private String selectedFlight;

    @Column(length = 4000)
    private String selectedOptionSnapshot;

    private String passengerId;
    private String pnr;
    private String itineraryId;
    private String disruptedSegmentId;
    private String flightNumber;
    private String originAirport;
    private String destinationAirport;
    private String scheduledDeparture;
    private String scheduledArrival;
    private Long itineraryVersion = 1L;
    private String finalDestination;

    @Column(length = 2000)
    private String mismatchReason;

    private Integer onwardSegmentsCount = 0;
    
    private LocalDateTime createdAt;
    
    @jakarta.persistence.Version
    private Long version;
    
    private LocalDateTime updatedAt;

    public RescueCase() {
    }

    public RescueCase(String id, String bookingId, String passengerName, String state,
                      String cancelledSegment, String flight, String impact,
                      String missedConnection, String hotelRisk) {
        this.id = id;
        this.bookingId = bookingId;
        this.passengerName = passengerName;
        this.state = state;
        this.cancelledSegment = cancelledSegment;
        this.flight = flight;
        this.impact = impact;
        this.missedConnection = missedConnection;
        this.hotelRisk = hotelRisk;
        this.createdAt = LocalDateTime.now();
        this.rescueAttemptId = "ATT-" + id + "-1";
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

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCancelledSegment() {
        return cancelledSegment;
    }

    public void setCancelledSegment(String cancelledSegment) {
        this.cancelledSegment = cancelledSegment;
    }

    public String getFlight() {
        return flight;
    }

    public void setFlight(String flight) {
        this.flight = flight;
    }

    public String getImpact() {
        return impact;
    }

    public void setImpact(String impact) {
        this.impact = impact;
    }

    public String getMissedConnection() {
        return missedConnection;
    }

    public void setMissedConnection(String missedConnection) {
        this.missedConnection = missedConnection;
    }

    public String getHotelRisk() {
        return hotelRisk;
    }

    public void setHotelRisk(String hotelRisk) {
        this.hotelRisk = hotelRisk;
    }

    public String getApprovedOptionId() {
        return approvedOptionId;
    }

    public void setApprovedOptionId(String approvedOptionId) {
        this.approvedOptionId = approvedOptionId;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getNewPnr() {
        return newPnr;
    }

    public void setNewPnr(String newPnr) {
        this.newPnr = newPnr;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getManualNextStep() {
        return manualNextStep;
    }

    public void setManualNextStep(String manualNextStep) {
        this.manualNextStep = manualNextStep;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getPassengerPhone() {
        return passengerPhone;
    }

    public void setPassengerPhone(String passengerPhone) {
        this.passengerPhone = passengerPhone;
    }

    public String getPassengerDecision() {
        return passengerDecision;
    }

    public void setPassengerDecision(String passengerDecision) {
        this.passengerDecision = passengerDecision;
    }

    public String getSelectedOptionId() {
        return selectedOptionId;
    }

    public void setSelectedOptionId(String selectedOptionId) {
        this.selectedOptionId = selectedOptionId;
    }

    public Integer getPassengerConfirmedAmount() {
        return passengerConfirmedAmount;
    }

    public void setPassengerConfirmedAmount(Integer passengerConfirmedAmount) {
        this.passengerConfirmedAmount = passengerConfirmedAmount;
    }

    public String getPassengerConfirmedCurrency() {
        return passengerConfirmedCurrency;
    }

    public void setPassengerConfirmedCurrency(String passengerConfirmedCurrency) {
        this.passengerConfirmedCurrency = passengerConfirmedCurrency;
    }

    public LocalDateTime getPassengerOtpVerifiedAt() {
        return passengerOtpVerifiedAt;
    }

    public void setPassengerOtpVerifiedAt(LocalDateTime passengerOtpVerifiedAt) {
        this.passengerOtpVerifiedAt = passengerOtpVerifiedAt;
    }

    public Integer getReconciledConfidence() {
        return reconciledConfidence;
    }

    public void setReconciledConfidence(Integer reconciledConfidence) {
        this.reconciledConfidence = reconciledConfidence;
    }

    public String getDisruptionEvidence() {
        return disruptionEvidence;
    }

    public void setDisruptionEvidence(String disruptionEvidence) {
        this.disruptionEvidence = disruptionEvidence;
    }

    public String getWeatherSummary() {
        return weatherSummary;
    }

    public void setWeatherSummary(String weatherSummary) {
        this.weatherSummary = weatherSummary;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }

    public String getTicketStatus() {
        return ticketStatus;
    }

    public void setTicketStatus(String ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    public String getNotificationStatus() {
        return notificationStatus;
    }

    public void setNotificationStatus(String notificationStatus) {
        this.notificationStatus = notificationStatus;
    }

    public String getCrmSyncStatus() {
        return crmSyncStatus;
    }

    public void setCrmSyncStatus(String crmSyncStatus) {
        this.crmSyncStatus = crmSyncStatus;
    }

    public String getRescueAttemptId() {
        if (rescueAttemptId == null && id != null) {
            rescueAttemptId = "ATT-" + id + "-1";
        }
        return rescueAttemptId;
    }

    public void setRescueAttemptId(String rescueAttemptId) {
        this.rescueAttemptId = rescueAttemptId;
    }

    public String getProviderStatus() {
        return providerStatus != null ? providerStatus : "SIMULATED";
    }

    public void setProviderStatus(String providerStatus) {
        this.providerStatus = providerStatus;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public void setProviderOrderId(String providerOrderId) {
        this.providerOrderId = providerOrderId;
    }

    public String getSelectedFlight() {
        return selectedFlight;
    }

    public void setSelectedFlight(String selectedFlight) {
        this.selectedFlight = selectedFlight;
    }

    public String getSelectedOptionSnapshot() {
        return selectedOptionSnapshot;
    }

    public void setSelectedOptionSnapshot(String selectedOptionSnapshot) {
        this.selectedOptionSnapshot = selectedOptionSnapshot;
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

    public String getDisruptedSegmentId() {
        return disruptedSegmentId;
    }

    public void setDisruptedSegmentId(String disruptedSegmentId) {
        this.disruptedSegmentId = disruptedSegmentId;
    }

    public String getFlightNumber() {
        return flightNumber != null ? flightNumber : flight;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
        if (this.flight == null) this.flight = flightNumber;
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

    public Long getItineraryVersion() {
        return itineraryVersion != null ? itineraryVersion : 1L;
    }

    public void setItineraryVersion(Long itineraryVersion) {
        this.itineraryVersion = itineraryVersion;
    }

    public String getFinalDestination() {
        return finalDestination;
    }

    public void setFinalDestination(String finalDestination) {
        this.finalDestination = finalDestination;
    }

    public String getMismatchReason() {
        return mismatchReason;
    }

    public void setMismatchReason(String mismatchReason) {
        this.mismatchReason = mismatchReason;
    }

    public Integer getOnwardSegmentsCount() {
        return onwardSegmentsCount != null ? onwardSegmentsCount : 0;
    }

    public void setOnwardSegmentsCount(Integer onwardSegmentsCount) {
        this.onwardSegmentsCount = onwardSegmentsCount;
    }
}
