package com.flyo.rescue.config;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.repository.BookingRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FlyoDataSeeder implements CommandLineRunner {

    private final BookingRepository bookingRepository;

    public FlyoDataSeeder(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public void run(String... args) {
        if (bookingRepository.count() == 0) {
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
                            "Arjun Reddy",
                            "BOM101",
                            "HYD → BOM",
                            "UK-833",
                            "Tonight 22:45",
                            "None",
                            "Airport transit",
                            "Flyo",
                            "Checked-in"
                    ),
                    new Booking(
                            "B5",
                            "Kavya Singh",
                            "DEL202",
                            "DEL → HYD",
                            "6E-901",
                            "Tomorrow 11:00",
                            "None",
                            "None",
                            "Flyo",
                            "On time"
                    )
            );
            bookingRepository.saveAll(seedBookings);
            System.out.println("Flyo Rescue: 5 Seed Bookings initialized successfully in H2 database.");
        }
    }
}
