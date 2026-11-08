package com.flyo.rescue.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Enterprise Production Observability & Prometheus Metrics Service.
 *
 * Tracks operational SLAs, provider latencies, booking failure modes,
 * distributed saga recoveries, and multi-tenant activity.
 */
@Service
public class FlyoMetricsService {

    private final MeterRegistry meterRegistry;

    public FlyoMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    /**
     * Record latency and outcome of external partner/GDS operations.
     */
    public void recordProviderLatency(String provider, String operation, String status, long durationMillis) {
        Timer.builder("flyo.provider.latency")
                .description("Latency of external provider and GDS calls")
                .tag("provider", provider != null ? provider : "unknown")
                .tag("operation", operation != null ? operation : "unknown")
                .tag("status", status != null ? status : "unknown")
                .register(meterRegistry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Track booking failures classified by provider and failure reason.
     */
    public void recordBookingFailure(String provider, String reason) {
        Counter.builder("flyo.booking.failures")
                .description("Count of booking failures classified by cause")
                .tag("provider", provider != null ? provider : "unknown")
                .tag("reason", reason != null ? reason : "unknown")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Track distributed saga recoveries (e.g. lease recovery, retry, dead-letter).
     */
    public void recordSagaRecovery(String action) {
        Counter.builder("flyo.saga.recovery")
                .description("Count of saga recovery actions after failure or restart")
                .tag("action", action != null ? action : "unknown")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Track payment and financial discrepancies discovered during reconciliation.
     */
    public void recordPaymentDiscrepancy(String tenantId, String type) {
        Counter.builder("flyo.payment.discrepancies")
                .description("Financial reconciliation discrepancies flagged for review")
                .tag("tenant", tenantId != null ? tenantId : "default")
                .tag("type", type != null ? type : "unknown")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Track tenant-level operations throughput.
     */
    public void recordTenantOperation(String tenantId, String operation) {
        Counter.builder("flyo.tenant.operations")
                .description("Operations throughput partitioned by tenant")
                .tag("tenant", tenantId != null ? tenantId : "default")
                .tag("operation", operation != null ? operation : "unknown")
                .register(meterRegistry)
                .increment();
    }
}
