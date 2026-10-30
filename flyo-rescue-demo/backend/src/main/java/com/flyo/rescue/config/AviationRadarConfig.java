package com.flyo.rescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "aviation.radar")
public class AviationRadarConfig {

    private boolean enabled = true;
    private String provider = "aviationstack"; // aviationstack, flightaware, cirium, mock
    private String apiKey = "";
    private String baseUrl = "https://api.aviationstack.com/v1";
    private int pollIntervalSeconds = 60;
    private boolean mockMode = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        this.mockMode = apiKey == null || apiKey.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public boolean isMockMode() {
        return mockMode || apiKey == null || apiKey.isBlank();
    }

    public void setMockMode(boolean mockMode) {
        this.mockMode = mockMode;
    }
}
