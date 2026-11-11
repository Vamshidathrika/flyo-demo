package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "passenger_phone_locks")
public class PassengerPhoneLock {

    @Id
    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_attempt_at", nullable = false)
    private LocalDateTime lastAttemptAt = LocalDateTime.now();

    @Column(name = "lock_reason", length = 64)
    private String lockReason;

    public PassengerPhoneLock() {
    }

    public PassengerPhoneLock(String phone, String tenantId, int failedAttempts, LocalDateTime lockedUntil, String lockReason) {
        this.phone = phone;
        this.tenantId = tenantId != null ? tenantId : "default";
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.lastAttemptAt = LocalDateTime.now();
        this.lockReason = lockReason;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(LocalDateTime lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public String getLockReason() {
        return lockReason;
    }

    public void setLockReason(String lockReason) {
        this.lockReason = lockReason;
    }
}
