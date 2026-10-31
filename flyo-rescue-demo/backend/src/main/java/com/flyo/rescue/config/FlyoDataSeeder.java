package com.flyo.rescue.config;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.OperatorAccount;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.OperatorAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FlyoDataSeeder implements CommandLineRunner {

    private final BookingRepository bookingRepository;
    private final OperatorAccountRepository operatorAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public FlyoDataSeeder(BookingRepository bookingRepository,
                          OperatorAccountRepository operatorAccountRepository,
                          PasswordEncoder passwordEncoder) {
        this.bookingRepository = bookingRepository;
        this.operatorAccountRepository = operatorAccountRepository;
        this.passwordEncoder = passwordEncoder;
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
                            "Checked-in",
                            "+919876543210"
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
                            "On time",
                            "+919811223344"
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
                            "On time",
                            "+919822334455"
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
                            "Checked-in",
                            "+919833445566"
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
                            "On time",
                            "+919844556677"
                    )
            );
            bookingRepository.saveAll(seedBookings);
            System.out.println("Flyo Rescue: 5 Seed Bookings initialized successfully.");
        }

        if (operatorAccountRepository.count() == 0) {
            List<OperatorAccount> initialAccounts = List.of(
                    new OperatorAccount("USR-1", "operator", passwordEncoder.encode("Operator123!"), "Flyo Operator", "ROLE_OPERATOR", true, "919876543210"),
                    new OperatorAccount("USR-2", "admin", passwordEncoder.encode("AdminSecret123!"), "System Admin", "ROLE_ADMIN", true, "919999999999"),
                    new OperatorAccount("USR-3", "auditor", passwordEncoder.encode("AuditorView123!"), "Compliance Auditor", "ROLE_AUDITOR", true, "918888888888")
            );
            operatorAccountRepository.saveAll(initialAccounts);
            System.out.println("Flyo Security: Initial RBAC Operator Accounts seeded.");
        }
    }
}
