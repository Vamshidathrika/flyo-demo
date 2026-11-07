package com.flyo.rescue.repository;

import com.flyo.rescue.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
    List<Booking> findAllByOrderByIdAsc();

    // Tenant-isolated query interfaces
    List<Booking> findByTenantIdOrderByIdAsc(String tenantId);
    Optional<Booking> findByIdAndTenantId(String id, String tenantId);
    long countByTenantId(String tenantId);
}
