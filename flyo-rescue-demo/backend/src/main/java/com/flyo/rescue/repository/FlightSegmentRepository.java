package com.flyo.rescue.repository;

import com.flyo.rescue.model.FlightSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FlightSegmentRepository extends JpaRepository<FlightSegment, String> {
    List<FlightSegment> findByBookingIdOrderBySegmentIndexAsc(String bookingId);
    List<FlightSegment> findByItineraryIdOrderBySegmentIndexAsc(String itineraryId);
    List<FlightSegment> findByPnrOrderBySegmentIndexAsc(String pnr);
    List<FlightSegment> findByFlightNumber(String flightNumber);
    Optional<FlightSegment> findByBookingIdAndSegmentIndex(String bookingId, int segmentIndex);
    void deleteByBookingId(String bookingId);
}
