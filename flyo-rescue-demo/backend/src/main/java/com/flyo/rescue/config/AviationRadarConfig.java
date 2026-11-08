package com.flyo.rescue.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConfigurationProperties(prefix = "aviation.radar")
public class AviationRadarConfig {

    @Autowired(required = false)
    private Environment environment;

    private boolean enabled = true;
    private String provider = "aviationstack"; // aviationstack, flightaware, cirium
    private String apiKey = "";
    private String baseUrl = "https://api.aviationstack.com/v1";
    private int pollIntervalSeconds = 60;
    private boolean mockMode = false;

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
        if (environment != null && environment.matchesProfiles("prod")) {
            return false; // Mock mode is strictly forbidden in production
        }
        if (environment == null || !environment.matchesProfiles("dev", "demo", "test")) {
            return false; // Mock mode is strictly forbidden unless explicitly activated in dev, demo, or test profiles
        }
        return mockMode;
    }

    public void setMockMode(boolean mockMode) {
        this.mockMode = mockMode;
    }
}
