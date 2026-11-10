package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.security.FlyoUserPrincipal;
import com.flyo.rescue.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final com.flyo.rescue.repository.RescueCaseRepository caseRepository;
    private final com.flyo.rescue.repository.RebookOptionRepository optionRepository;
    private final org.springframework.core.env.Environment environment;
    private final CanonicalItineraryService canonicalItineraryService;

    public BookingService(BookingRepository bookingRepository,
                          @org.springframework.context.annotation.Lazy com.flyo.rescue.repository.RescueCaseRepository caseRepository,
                          @org.springframework.context.annotation.Lazy com.flyo.rescue.repository.RebookOptionRepository optionRepository,
                          org.springframework.core.env.Environment environment,
                          @org.springframework.context.annotation.Lazy CanonicalItineraryService canonicalItineraryService) {
        this.bookingRepository = bookingRepository;
        this.caseRepository = caseRepository;
        this.optionRepository = optionRepository;
        this.environment = environment;
        this.canonicalItineraryService = canonicalItineraryService;
    }

    private boolean isGlobalAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof FlyoUserPrincipal p) {
            return p.getAuthorizedTenants().contains("*");
        }
        return false;
    }

    public List<Booking> getAllBookings() {
        String tenantId = TenantContext.getTenantId();
        List<Booking> list;
        if (isGlobalAdmin()) {
            list = new java.util.ArrayList<>(bookingRepository.findAll());
        } else {
            list = new java.util.ArrayList<>(bookingRepository.findByTenantIdOrderByIdAsc(tenantId));
        }
        list.sort(java.util.Comparator.comparing(Booking::getId));
        return list;
    }

    public Optional<Booking> getBookingById(String id) {
        Optional<Booking> opt = bookingRepository.findById(id);
        if (opt.isPresent()) {
            String tenantId = TenantContext.getTenantId();
            if (!isGlobalAdmin() && !tenantId.equals(opt.get().getTenantId())) {
                log.warn("[Tenant Security] Blocked cross-tenant booking access: booking {} belongs to {}, caller tenant is {}",
                        id, opt.get().getTenantId(), tenantId);
                return Optional.empty();
            }
        }
        return opt;
    }

    public Booking saveBooking(Booking booking) {
        if (booking.getId() == null || booking.getId().isBlank()) {
            booking.setId("B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        if (booking.getPnr() == null || booking.getPnr().isBlank()) {
            booking.setPnr("FLY" + UUID.randomUUID().toString().substring(0, 5).toUpperCase());
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
        if (booking.getPassengerPhone() == null || booking.getPassengerPhone().isBlank()) {
            booking.setPassengerPhone("+919876543210");
        }
        if (booking.getTenantId() == null || booking.getTenantId().isBlank()) {
            booking.setTenantId(TenantContext.getTenantId());
        }
        Booking saved = bookingRepository.save(booking);
        if (canonicalItineraryService != null) {
            try {
                canonicalItineraryService.normalizeAndPersistSegments(saved);
            } catch (Exception e) {
                log.warn("[BookingService] Failed to normalize segments for booking {}: {}", saved.getId(), e.getMessage());
            }
        }
        return saved;
    }

    @Transactional
    public boolean deleteBooking(String id) {
        Optional<Booking> opt = getBookingById(id);
        if (opt.isPresent()) {
            bookingRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Transactional
    public void clearAllBookings() {
        if (environment != null && (environment.matchesProfiles("prod") || !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "demo", "test")))) {
            throw new UnsupportedOperationException("Production data clear is strictly prohibited.");
        }
        if (optionRepository != null) {
            optionRepository.deleteAll();
        }
        if (caseRepository != null) {
            caseRepository.deleteAll();
        }
        if (isGlobalAdmin()) {
            bookingRepository.deleteAll();
        } else {
            String tenantId = TenantContext.getTenantId();
            List<Booking> tenantBookings = bookingRepository.findByTenantIdOrderByIdAsc(tenantId);
            bookingRepository.deleteAll(tenantBookings);
        }
    }

    @Transactional
    public List<Booking> resetSeedBookings() {
        if (environment != null && (environment.matchesProfiles("prod") || !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "demo", "test")))) {
            throw new UnsupportedOperationException("Production seed reset is strictly prohibited.");
        }
        if (optionRepository != null) {
            optionRepository.deleteAll();
        }
        if (caseRepository != null) {
            caseRepository.deleteAll();
        }
        String currentTenant = TenantContext.getTenantId();
        if (isGlobalAdmin()) {
            bookingRepository.deleteAll();
        } else {
            List<Booking> tenantBookings = bookingRepository.findByTenantIdOrderByIdAsc(currentTenant);
            bookingRepository.deleteAll(tenantBookings);
        }

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
                        "Vikram Malhotra",
                        "BOM321",
                        "HYD → BOM",
                        "QP-1102",
                        "Tomorrow 11:45",
                        "None",
                        "None",
                        "Flyo",
                        "On time",
                        "+919833445566"
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
                        "On time",
                        "+919844556677"
                )
        );
        for (Booking b : seedBookings) {
            b.setTenantId(currentTenant);
        }
        return bookingRepository.saveAll(seedBookings);
    }
}
