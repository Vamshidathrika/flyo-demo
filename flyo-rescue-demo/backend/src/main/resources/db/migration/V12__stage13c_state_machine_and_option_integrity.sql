-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V12
-- STAGE 13C: CRITICAL RESCUE WORKFLOW INTEGRITY, BOOKING CONSISTENCY & PASSENGER INTENT ENFORCEMENT
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- 1. Extend rescue_cases with attempt tracking, provider boundary, and option snapshot
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS rescue_attempt_id VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS provider_status VARCHAR(64) DEFAULT 'SIMULATED';
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS provider_order_id VARCHAR(128);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS selected_flight VARCHAR(32);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS selected_option_snapshot TEXT;

-- 2. Extend rebook_options with comprehensive provider offer metadata
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS provider VARCHAR(64) DEFAULT 'StarSky Mock Airline Engine';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS provider_offer_id VARCHAR(128);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS flight_segments TEXT;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS departure_timestamp TIMESTAMP;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS arrival_timestamp TIMESTAMP;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS departure_time_zone VARCHAR(32) DEFAULT 'Asia/Kolkata';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS arrival_time_zone VARCHAR(32) DEFAULT 'Asia/Kolkata';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS departure_timezone VARCHAR(32) DEFAULT 'Asia/Kolkata';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS arrival_timezone VARCHAR(32) DEFAULT 'Asia/Kolkata';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS connection_details VARCHAR(512);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS currency VARCHAR(8) DEFAULT 'INR';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS fare_expiry TIMESTAMP;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS baggage_conditions VARCHAR(512) DEFAULT 'Standard carrier allowance; separate-ticket connections require airport re-check';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS fare_rules VARCHAR(512) DEFAULT 'Non-refundable; involuntary disruption change permitted per carrier policy';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS onward_itinerary VARCHAR(512);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS calculation_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS rescue_attempt_id VARCHAR(64);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';

-- 3. Extend passenger_otp_challenges with attempt binding, invalidation tracking, and tenant isolation
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS rescue_attempt_id VARCHAR(64);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS invalidated BOOLEAN DEFAULT FALSE;
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS invalidated_at TIMESTAMP;
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS invalidated_reason VARCHAR(128);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS permitted_action VARCHAR(64) DEFAULT 'REBOOK_CONFIRMATION';

-- 4. Create index for attempt and state lookup
CREATE INDEX IF NOT EXISTS idx_rescue_attempt ON rescue_cases(rescue_attempt_id);
CREATE INDEX IF NOT EXISTS idx_otp_attempt ON passenger_otp_challenges(rescue_attempt_id);
CREATE INDEX IF NOT EXISTS idx_otp_invalidation ON passenger_otp_challenges(invalidated, case_id);
