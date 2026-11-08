package com.flyo.rescue.integration.amadeus;

public record AmadeusCredentials(String clientId, String clientSecret, String baseUrl) {
    public boolean isValid() {
        return clientId != null && !clientId.isBlank() && !clientId.contains("dummy")
                && clientSecret != null && !clientSecret.isBlank() && !clientSecret.contains("dummy");
    }
}
