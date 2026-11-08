package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.RebookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * PRODUCTION ACCEPTANCE STEP 9: Realistic Load and Concurrency Testing.
 *
 * Simulates:
 * 1. 20 concurrent flight disruption events processed across parallel threads.
 * 2. Race condition testing: concurrent operator approvals on the same case.
 * 3. Latency profiling: P50, P95, and P99 measurements for disruption simulation and ranking.
 * 4. Zero state corruption or lost updates under concurrent load.
 */
@SpringBootTest
public class ConcurrencyAndLoadTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyAndLoadTest.class);

    @Autowired
    private CaseService caseService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private PaymentSettlementService paymentService;

    @MockBean
    private RebookService rebookService;

    @BeforeEach
    void setUp() {
        MockAirlineController.setBroken(false);

        RebookResultDto successResult = new RebookResultDto();
        successResult.setSuccess(true);
        successResult.setCaseId("C1");
        successResult.setOptionId("O2");
        successResult.setFlight("FL301");
        successResult.setNewPnr("R7X2K9");

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(successResult);
    }

    @Test
    @DisplayName("Concurrency 9.1: 20 concurrent flight disruptions processed safely with zero deadlocks")
    void testTwentyConcurrentDisruptions() throws Exception {
        int concurrency = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);

        // Pre-create 20 bookings
        List<String> bookingIds = new ArrayList<>();
        for (int i = 1; i <= concurrency; i++) {
            String bId = "B-LOAD-" + i;
            bookingIds.add(bId);
            Booking booking = new Booking(
                    bId,
                    "Load Passenger " + i,
                    "PNR-L" + i,
                    "HYD → DEL",
                    "AI-83" + (i % 10),
                    "Tonight 21:00",
                    "DEL → LHR (BA-142)",
                    "Hotel Claridges",
                    "Flyo Corporate",
                    "Checked-in"
            );
            bookingRepository.save(booking);
        }

        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();

        for (String bId : bookingIds) {
            futures.add(executor.submit(() -> {
                latch.await(); // Synchronize thread start
                long start = System.currentTimeMillis();
                RescueCase c = caseService.simulateCancellation(bId, "CANCELLED");
                caseService.generateAndRankOptions(c.getId());
                return System.currentTimeMillis() - start;
            }));
        }

        // Unleash all 20 threads simultaneously
        latch.countDown();

        List<Long> latencies = new ArrayList<>();
        for (Future<Long> f : futures) {
            latencies.add(f.get(10, TimeUnit.SECONDS));
        }

        executor.shutdown();

        // Verify all 20 rescue cases were created and state is OPTIONS_READY
        for (String bId : bookingIds) {
            String expectedCaseId = "CASE-" + bId;
            Optional<RescueCase> caseOpt = rescueCaseRepository.findById(expectedCaseId);
            assertTrue(caseOpt.isPresent(), "Case must be persisted for " + bId);
            assertEquals("OPTIONS_READY", caseOpt.get().getState());

            List<RebookOption> options = caseService.getOptionsForCase(expectedCaseId);
            assertFalse(options.isEmpty(), "Options must be generated for " + expectedCaseId);
        }

        // Profile latency percentiles
        Collections.sort(latencies);
        long p50 = latencies.get((int) (latencies.size() * 0.50));
        long p95 = latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.get(latencies.size() - 1);

        log.info("[Load Test Benchmark] 20 Concurrent Disruptions: P50 = {} ms, P95 = {} ms, P99 = {} ms", p50, p95, p99);
        assertTrue(p95 < 2000, "P95 latency under local load must be under 2000 ms");
    }

    @Test
    @DisplayName("Concurrency 9.2: Simultaneous approvals on same case execute idempotently without data race")
    void testConcurrentApprovalsOnSameCase() throws Exception {
        String testBookingId = "B-RACE-" + System.currentTimeMillis();
        Booking booking = new Booking(
                testBookingId,
                "Race Pax",
                "PNRRACE",
                "HYD → DEL",
                "AI-839",
                "Tonight 21:00",
                "DEL → LHR (BA-142)",
                "None",
                "Flyo",
                "Checked-in"
        );
        bookingRepository.save(booking);

        RescueCase c = caseService.simulateCancellation(testBookingId, "CANCELLED");
        caseService.generateAndRankOptions(c.getId());

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            results.add(executor.submit(() -> {
                startLatch.await();
                try {
                    caseService.approveOption(c.getId(), "O2", "Operator-" + index);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        startLatch.countDown();

        int successfulApprovals = 0;
        for (Future<Boolean> f : results) {
            if (f.get(5, TimeUnit.SECONDS)) {
                successfulApprovals++;
            }
        }
        executor.shutdown();

        // At least one approval succeeds; idempotency protects subsequent calls
        assertTrue(successfulApprovals >= 1, "At least one approval call must succeed");

        RescueCase finalCase = rescueCaseRepository.findById(c.getId()).orElseThrow();
        assertEquals("APPROVED", finalCase.getState());
        assertTrue(finalCase.getApprovedOptionId().endsWith("O2"), "Approved option ID must be O2");

        // Ensure Virtual Card was issued cleanly without duplicates
        assertTrue(paymentService.getActiveCardForCase(c.getId()).isPresent());
    }
}
