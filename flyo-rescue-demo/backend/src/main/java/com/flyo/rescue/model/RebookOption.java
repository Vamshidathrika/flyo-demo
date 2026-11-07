package com.flyo.rescue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rebook_options")
public class RebookOption {

    @Id
    private String id;
    private String caseId;
    private String flight;
    private String route;
    private String departure;
    private String arrival;
    private int connectionMinutes;
    private int extraCost;
    private boolean savesConnection;
    private String reason;
    private int rank;
    private Integer riskScore = 15;
    private String riskLevel = "LOW";
    private String riskFactors;

    public RebookOption() {
    }

    public RebookOption(String id, String caseId, String flight, String route, String departure,
                        String arrival, int connectionMinutes, int extraCost,
                        boolean savesConnection, String reason, int rank) {
        this.id = id;
        this.caseId = caseId;
        this.flight = flight;
        this.route = route;
        this.departure = departure;
        this.arrival = arrival;
        this.connectionMinutes = connectionMinutes;
        this.extraCost = extraCost;
        this.savesConnection = savesConnection;
        this.reason = reason;
        this.rank = rank;
    }

    public RebookOption(String id, String caseId, String flight, String route, String departure,
                        String arrival, int connectionMinutes, int extraCost,
                        int rank, boolean savesConnection, String reason) {
        this(id, caseId, flight, route, departure, arrival, connectionMinutes, extraCost, savesConnection, reason, rank);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getFlight() {
        return flight;
    }

    public void setFlight(String flight) {
        this.flight = flight;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getDeparture() {
        return departure;
    }

    public void setDeparture(String departure) {
        this.departure = departure;
    }

    public String getArrival() {
        return arrival;
    }

    public void setArrival(String arrival) {
        this.arrival = arrival;
    }

    public int getConnectionMinutes() {
        return connectionMinutes;
    }

    public void setConnectionMinutes(int connectionMinutes) {
        this.connectionMinutes = connectionMinutes;
    }

    public int getExtraCost() {
        return extraCost;
    }

    public void setExtraCost(int extraCost) {
        this.extraCost = extraCost;
    }

    public boolean isSavesConnection() {
        return savesConnection;
    }

    public void setSavesConnection(boolean savesConnection) {
        this.savesConnection = savesConnection;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getRiskFactors() {
        return riskFactors;
    }

    public void setRiskFactors(String riskFactors) {
        this.riskFactors = riskFactors;
    }
}
