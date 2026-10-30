package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;

    public BookingService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    public List<Booking> getAllBookings() {
        List<Booking> list = new java.util.ArrayList<>(bookingRepository.findAll());
        list.sort((a, b) -> {
            if ("B1".equalsIgnoreCase(a.getId())) return -1;
            if ("B1".equalsIgnoreCase(b.getId())) return 1;
            return a.getId().compareToIgnoreCase(b.getId());
        });
        return list;
    }

    public Optional<Booking> getBookingById(String id) {
        return bookingRepository.findById(id);
    }

    public Booking saveBooking(Booking booking) {
        if (booking.getId() == null || booking.getId().isBlank()) {
            booking.setId("B-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase());
        }
        if (booking.getPnr() == null || booking.getPnr().isBlank()) {
            booking.setPnr("PNR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        }
        if (booking.getAgency() == null || booking.getAgency().isBlank()) {
            booking.setAgency("Flyo");
        }
        if (booking.getStatus() == null || booking.getStatus().isBlank()) {
            booking.setStatus("Checked-in");
        }
        if (booking.getOnwardFlight() == null || booking.getOnwardFlight().isBlank()) {
            booking.setOnwardFlight("None");
        }
        if (booking.getHotelNote() == null || booking.getHotelNote().isBlank()) {
            booking.setHotelNote("None");
        }
        return bookingRepository.save(booking);
    }

    @Transactional
    public boolean deleteBooking(String id) {
        if (bookingRepository.existsById(id)) {
            bookingRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Transactional
    public void clearAllBookings() {
        bookingRepository.deleteAll();
    }

    @Transactional
    public List<Booking> resetSeedBookings() {
        bookingRepository.deleteAll();
        List<Booking> seedBookings = List.of(
                new Booking(
                        "B1",
                        "Ananya Sharma",
                        "HYD123",
                        "HYD → DEL",
                        "AI-839",
                        "Tonight 21:00",
                        "DEL → Toronto (YYZ) Tomorrow 09:30",
                        "Delhi hotel non-refundable",
                        "Flyo",
                        "Checked-in"
                ),
                new Booking(
                        "B2",
                        "Rohit Verma",
                        "BLR456",
                        "HYD → BLR",
                        "6E-402",
                        "Tomorrow 07:15",
                        "None",
                        "None",
                        "Flyo",
                        "On time"
                ),
                new Booking(
                        "B3",
                        "Priya Nair",
                        "MAA789",
                        "HYD → MAA",
                        "AI-542",
                        "Tomorrow 08:30",
                        "None",
                        "None",
                        "Flyo",
                        "On time"
                ),
                new Booking(
                        "B4",
                        "Vikram Malhotra",
                        "BOM321",
                        "HYD → BOM",
                        "QP-1102",
                        "Tomorrow 11:45",
                        "None",
                        "None",
                        "Flyo",
                        "On time"
                ),
                new Booking(
                        "B5",
                        "Sneha Patel",
                        "CCU654",
                        "HYD → CCU",
                        "UK-825",
                        "Tomorrow 14:20",
                        "None",
                        "None",
                        "Flyo",
                        "On time"
                )
        );
        return bookingRepository.saveAll(seedBookings);
    }
}
