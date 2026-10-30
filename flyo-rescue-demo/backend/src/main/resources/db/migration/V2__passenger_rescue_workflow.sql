-- V2: Passenger-Driven WhatsApp Flight Recovery Workflow Schema Extension

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS passenger_phone VARCHAR(32);

ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS passenger_phone VARCHAR(32);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS passenger_decision VARCHAR(32);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS selected_option_id VARCHAR(36);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS passenger_confirmed_amount INT;
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS passenger_confirmed_currency VARCHAR(8);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS passenger_otp_verified_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS passenger_otp_challenges (
    id VARCHAR(36) PRIMARY KEY,
    case_id VARCHAR(36) NOT NULL,
    passenger_phone VARCHAR(32) NOT NULL,
    option_id VARCHAR(36) NOT NULL,
    amount INT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    otp_hash VARCHAR(128) NOT NULL,
    attempts INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    verified BOOLEAN DEFAULT FALSE,
    verified_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pax_otp_case_phone ON passenger_otp_challenges(case_id, passenger_phone);
