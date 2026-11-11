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

    // =========================================================================
    // STAGE 14: PRODUCTION RESILIENCE & INTEGRITY METRICS
    // =========================================================================

    public void recordBookingResolutionFailure(String reason) {
        Counter.builder("flyo.booking.resolution.failures")
                .description("Failures resolving booking or segment from alert")
                .tag("reason", reason != null ? reason : "unknown")
                .register(meterRegistry)
                .increment();
    }

    public void recordItineraryMismatch(String field, String reason) {
        Counter.builder("flyo.itinerary.mismatches")
                .description("Discrepancies in route, flight number, or destination")
                .tag("field", field != null ? field : "unknown")
                .tag("reason", reason != null ? reason : "unknown")
                .register(meterRegistry)
                .increment();
    }

    public void recordDuplicateExecutionAttempt(String scope) {
        Counter.builder("flyo.duplicate.execution.attempts")
                .description("Suppressed duplicate execution or notification attempts")
                .tag("scope", scope != null ? scope : "unknown")
                .register(meterRegistry)
                .increment();
    }

    public void recordOtpVerificationFailure(String reason) {
        Counter.builder("flyo.otp.verification.failures")
                .description("Failed passenger or operator OTP verifications")
                .tag("reason", reason != null ? reason : "unknown")
                .register(meterRegistry)
                .increment();
    }

    public void recordProviderTimeout(String provider, String operation) {
        Counter.builder("flyo.provider.timeouts")
                .description("Provider network timeouts requiring reconciliation")
                .tag("provider", provider != null ? provider : "unknown")
                .tag("operation", operation != null ? operation : "unknown")
                .register(meterRegistry)
                .increment();
    }

    public void recordWebhookDelay(String provider, long delayMillis) {
        Timer.builder("flyo.webhook.delays")
                .description("Time between webhook event timestamp and arrival")
                .tag("provider", provider != null ? provider : "unknown")
                .register(meterRegistry)
                .record(delayMillis, TimeUnit.MILLISECONDS);
    }

    public void recordReconciliationFailure(String caseId, String reason) {
        Counter.builder("flyo.reconciliation.failures")
                .description("Multi-way booking reconciliation failures")
                .tag("reason", reason != null ? reason : "unknown")
                .register(meterRegistry)
                .increment();
    }

    public void recordNotificationDeliveryFailure(String channel, String reason) {
        Counter.builder("flyo.notification.delivery.failures")
                .description("Failures dispatching customer communications")
                .tag("channel", channel != null ? channel : "unknown")
                .tag("reason", reason != null ? reason : "unknown")
                .register(meterRegistry)
                .increment();
    }
}
