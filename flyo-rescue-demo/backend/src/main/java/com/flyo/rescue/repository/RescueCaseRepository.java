package com.flyo.rescue.repository;

import com.flyo.rescue.model.RescueCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RescueCaseRepository extends JpaRepository<RescueCase, String> {
    Optional<RescueCase> findByBookingId(String bookingId);
}
