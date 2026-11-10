-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V13
-- P0: CANONICAL ITINERARY IDENTITY, FLIGHT SEGMENT MODEL & TRANSACTION INTEGRITY
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- 1. Canonical Flight Segments Table
CREATE TABLE IF NOT EXISTS flight_segments (
    id VARCHAR(64) PRIMARY KEY,
    booking_id VARCHAR(36) NOT NULL,
    passenger_id VARCHAR(64),
    pnr VARCHAR(32) NOT NULL,
    itinerary_id VARCHAR(64) NOT NULL,
    segment_index INT NOT NULL DEFAULT 1,
    flight_number VARCHAR(32) NOT NULL,
    origin_airport VARCHAR(16) NOT NULL,
    destination_airport VARCHAR(16) NOT NULL,
    scheduled_departure VARCHAR(64) NOT NULL,
    scheduled_arrival VARCHAR(64),
    status VARCHAR(32) DEFAULT 'CONFIRMED',
    itinerary_version BIGINT DEFAULT 1,
    tenant_id VARCHAR(64) DEFAULT 'tenant_default',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_flight_segments_booking ON flight_segments(booking_id);
CREATE INDEX IF NOT EXISTS idx_flight_segments_pnr ON flight_segments(pnr);
CREATE INDEX IF NOT EXISTS idx_flight_segments_itin ON flight_segments(itinerary_id);
CREATE INDEX IF NOT EXISTS idx_flight_segments_flight ON flight_segments(flight_number);

-- 2. Extend bookings table with canonical identity
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS passenger_id VARCHAR(64);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS itinerary_id VARCHAR(64);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS itinerary_version BIGINT DEFAULT 1;

-- 3. Extend rescue_cases table with canonical segment reference and reconciliation fields
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS passenger_id VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS pnr VARCHAR(32);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS itinerary_id VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS disrupted_segment_id VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS flight_number VARCHAR(32);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS origin_airport VARCHAR(16);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS destination_airport VARCHAR(16);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS scheduled_departure VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS scheduled_arrival VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS itinerary_version BIGINT DEFAULT 1;
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS final_destination VARCHAR(16);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS mismatch_reason VARCHAR(2000);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS onward_segments_count INT DEFAULT 0;

-- 4. Extend rebook_options table with source segment and canonical verification keys
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS booking_id VARCHAR(36);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS incident_id VARCHAR(36);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS source_segment_id VARCHAR(64);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS itinerary_version BIGINT DEFAULT 1;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS origin_airport VARCHAR(16);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS destination_airport VARCHAR(16);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS final_destination VARCHAR(16);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS deterministic_cache_key VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_rebook_options_incident_seg ON rebook_options(incident_id, source_segment_id);
CREATE INDEX IF NOT EXISTS idx_rebook_options_cache_key ON rebook_options(deterministic_cache_key);
