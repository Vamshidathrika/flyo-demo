package com.flyo.rescue.model;

import com.flyo.rescue.integration.ProviderCapabilityStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Authoritative evidence record documenting provider contract validation,
 * environment boundaries, authentication status, and known limitations.
 */
@Entity
@Table(name = "provider_evidence_records")
public class ProviderEvidenceRecord {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String providerId;

    @Column(length = 128, nullable = false)
    private String providerName;

    @Column(length = 32, nullable = false)
    private String environment; // "SANDBOX", "PRODUCTION", "LOCAL_SIMULATOR"

    @Column(length = 32, nullable = false)
    private String apiVersion;

    @Column(length = 128, nullable = false)
    private String capabilityTested;

    @Enumerated(EnumType.STRING)
    @Column(length = 64, nullable = false)
    private ProviderCapabilityStatus status;

    @Column(length = 32, nullable = false)
    private String contractTestResult; // "PASS", "FAIL", "NOT_TESTED"

    @Column(length = 32, nullable = false)
    private String authResult; // "VERIFIED", "PENDING_CREDENTIALS", "SIMULATED"

    @Column(columnDefinition = "TEXT")
    private String limitations;

    private LocalDateTime lastHealthCheck = LocalDateTime.now();

    @Column(length = 128, nullable = false)
    private String sourceOfVerification;

    public ProviderEvidenceRecord() {
    }

    public ProviderEvidenceRecord(String id, String providerId, String providerName, String environment,
                                  String apiVersion, String capabilityTested, ProviderCapabilityStatus status,
                                  String contractTestResult, String authResult, String limitations,
                                  String sourceOfVerification) {
        this.id = id;
        this.providerId = providerId;
        this.providerName = providerName;
        this.environment = environment;
        this.apiVersion = apiVersion;
        this.capabilityTested = capabilityTested;
        this.status = status;
        this.contractTestResult = contractTestResult;
        this.authResult = authResult;
        this.limitations = limitations;
        this.sourceOfVerification = sourceOfVerification;
        this.lastHealthCheck = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    public String getCapabilityTested() { return capabilityTested; }
    public void setCapabilityTested(String capabilityTested) { this.capabilityTested = capabilityTested; }

    public ProviderCapabilityStatus getStatus() { return status; }
    public void setStatus(ProviderCapabilityStatus status) { this.status = status; }

    public String getContractTestResult() { return contractTestResult; }
    public void setContractTestResult(String contractTestResult) { this.contractTestResult = contractTestResult; }

    public String getAuthResult() { return authResult; }
    public void setAuthResult(String authResult) { this.authResult = authResult; }

    public String getLimitations() { return limitations; }
    public void setLimitations(String limitations) { this.limitations = limitations; }

    public LocalDateTime getLastHealthCheck() { return lastHealthCheck; }
    public void setLastHealthCheck(LocalDateTime lastHealthCheck) { this.lastHealthCheck = lastHealthCheck; }

    public String getSourceOfVerification() { return sourceOfVerification; }
    public void setSourceOfVerification(String sourceOfVerification) { this.sourceOfVerification = sourceOfVerification; }
}
