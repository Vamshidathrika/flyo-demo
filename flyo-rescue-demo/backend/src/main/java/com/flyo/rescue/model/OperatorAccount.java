package com.flyo.rescue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "operator_accounts")
public class OperatorAccount {

    @Id
    private String id;

    private String username;
    private String passwordHash;
    private String fullName;
    private String role; // ROLE_OPERATOR, ROLE_ADMIN, ROLE_AUDITOR
    private boolean enabled;
    private String phoneNumber;

    private String tenantId = "tenant_default";
    private String authorizedTenants = "tenant_default";

    public OperatorAccount() {
    }

    public OperatorAccount(String id, String username, String passwordHash, String fullName, String role, boolean enabled, String phoneNumber) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
        this.phoneNumber = phoneNumber;
        this.tenantId = "tenant_default";
        this.authorizedTenants = "tenant_default";
    }

    public OperatorAccount(String id, String username, String passwordHash, String fullName, String role, boolean enabled, String phoneNumber,
                           String tenantId, String authorizedTenants) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
        this.phoneNumber = phoneNumber;
        this.tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : "tenant_default";
        this.authorizedTenants = (authorizedTenants != null && !authorizedTenants.isBlank()) ? authorizedTenants : this.tenantId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getTenantId() {
        return tenantId != null ? tenantId : "tenant_default";
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getAuthorizedTenants() {
        return authorizedTenants != null ? authorizedTenants : getTenantId();
    }

    public void setAuthorizedTenants(String authorizedTenants) {
        this.authorizedTenants = authorizedTenants;
    }

    public Set<String> getAuthorizedTenantsSet() {
        if (authorizedTenants == null || authorizedTenants.isBlank()) {
            return Set.of(getTenantId());
        }
        return Arrays.stream(authorizedTenants.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean isAuthorizedForTenant(String targetTenant) {
        if (targetTenant == null || targetTenant.isBlank()) return false;
        Set<String> set = getAuthorizedTenantsSet();
        if (set.contains("*")) return true;
        return set.contains(targetTenant) || targetTenant.equals(getTenantId());
    }
}
