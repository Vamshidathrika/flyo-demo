package com.flyo.rescue.repository;

import com.flyo.rescue.model.RescueCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RescueCaseRepository extends JpaRepository<RescueCase, String> {
    Optional<RescueCase> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);
    Optional<RescueCase> findByBookingId(String bookingId);

    // Tenant-isolated query interfaces
    List<RescueCase> findByTenantId(String tenantId);
    Optional<RescueCase> findByIdAndTenantId(String id, String tenantId);
    Optional<RescueCase> findFirstByBookingIdAndTenantIdOrderByCreatedAtDesc(String bookingId, String tenantId);
    List<RescueCase> findByTenantIdAndState(String tenantId, String state);
    long countByTenantId(String tenantId);
}
