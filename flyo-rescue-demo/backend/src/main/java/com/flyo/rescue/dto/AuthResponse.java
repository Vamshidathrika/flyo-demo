package com.flyo.rescue.dto;

import java.util.List;

public class AuthResponse {

    private boolean success;
    private String token;
    private String tokenType;
    private long expiresInMs;
    private String username;
    private String fullName;
    private List<String> roles;
    private String message;

    public AuthResponse() {
    }

    public AuthResponse(boolean success, String token, String tokenType, long expiresInMs,
                        String username, String fullName, List<String> roles, String message) {
        this.success = success;
        this.token = token;
        this.tokenType = tokenType;
        this.expiresInMs = expiresInMs;
        this.username = username;
        this.fullName = fullName;
        this.roles = roles;
        this.message = message;
    }

    public static AuthResponse success(String token, long expiresInMs, String username, String fullName, List<String> roles) {
        return new AuthResponse(true, token, "Bearer", expiresInMs, username, fullName, roles, "Authentication successful");
    }

    public static AuthResponse failure(String message) {
        return new AuthResponse(false, null, null, 0, null, null, List.of(), message);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresInMs() {
        return expiresInMs;
    }

    public void setExpiresInMs(long expiresInMs) {
        this.expiresInMs = expiresInMs;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
