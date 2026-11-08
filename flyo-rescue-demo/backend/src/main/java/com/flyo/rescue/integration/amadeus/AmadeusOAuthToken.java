package com.flyo.rescue.integration.amadeus;

import java.time.Instant;

public class AmadeusOAuthToken {
    private final String accessToken;
    private final String tokenType;
    private final int expiresIn;
    private final Instant expiresAt;

    public AmadeusOAuthToken(String accessToken, String tokenType, int expiresIn) {
        this.accessToken = accessToken;
        this.tokenType = tokenType != null ? tokenType : "Bearer";
        this.expiresIn = expiresIn;
        // Expire 60 seconds early to prevent edge-of-window authorization failures
        this.expiresAt = Instant.now().plusSeconds(Math.max(10, expiresIn - 60));
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public int getExpiresIn() {
        return expiresIn;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
