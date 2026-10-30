package com.flyo.rescue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "passenger_otp_challenges")
public class PassengerOtpChallenge {

    @Id
    private String id;
    private String caseId;
    private String passengerPhone;
    private String optionId;
    private int amount;
    private String currency;
    private String otpHash;
    private int attempts;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private boolean verified;
    private LocalDateTime verifiedAt;

    public PassengerOtpChallenge() {
    }

    public PassengerOtpChallenge(String id, String caseId, String passengerPhone, String optionId,
                                 int amount, String currency, String otpHash,
                                 LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.id = id;
        this.caseId = caseId;
        this.passengerPhone = passengerPhone;
        this.optionId = optionId;
        this.amount = amount;
        this.currency = currency;
        this.otpHash = otpHash;
        this.attempts = 0;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.verified = false;
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

    public String getPassengerPhone() {
        return passengerPhone;
    }

    public void setPassengerPhone(String passengerPhone) {
        this.passengerPhone = passengerPhone;
    }

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public void setOtpHash(String otpHash) {
        this.otpHash = otpHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }
}
