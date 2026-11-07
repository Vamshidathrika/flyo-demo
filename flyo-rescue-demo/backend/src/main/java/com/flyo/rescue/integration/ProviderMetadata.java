package com.flyo.rescue.integration;

import com.flyo.rescue.model.ProviderEvidenceRecord;

import java.util.List;

public class ProviderMetadata {

    private final String providerId;
    private final String providerName;
    private final ProviderCategory category;
    private ProviderCapabilityStatus capabilityStatus;
    private final List<String> supportedProtocols;
    private final int averageLatencyMs;
    private final String description;
    private final boolean productionCertified;
    private ProviderEvidenceRecord evidenceRecord;

    public ProviderMetadata(String providerId,
                            String providerName,
                            ProviderCategory category,
                            ProviderCapabilityStatus capabilityStatus,
                            List<String> supportedProtocols,
                            int averageLatencyMs,
                            String description,
                            boolean productionCertified) {
        this.providerId = providerId;
        this.providerName = providerName;
        this.category = category;
        this.capabilityStatus = capabilityStatus;
        this.supportedProtocols = supportedProtocols;
        this.averageLatencyMs = averageLatencyMs;
        this.description = description;
        this.productionCertified = productionCertified;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderName() {
        return providerName;
    }

    public ProviderCategory getCategory() {
        return category;
    }

    public ProviderCapabilityStatus getCapabilityStatus() {
        return capabilityStatus;
    }

    public void setCapabilityStatus(ProviderCapabilityStatus capabilityStatus) {
        this.capabilityStatus = capabilityStatus;
    }

    public List<String> getSupportedProtocols() {
        return supportedProtocols;
    }

    public int getAverageLatencyMs() {
        return averageLatencyMs;
    }

    public String getDescription() {
        return description;
    }

    public boolean isProductionCertified() {
        return productionCertified;
    }

    public ProviderEvidenceRecord getEvidenceRecord() {
        return evidenceRecord;
    }

    public void setEvidenceRecord(ProviderEvidenceRecord evidenceRecord) {
        this.evidenceRecord = evidenceRecord;
    }
}
