-- V3: Multi-Source Aviation Intelligence, Weather Telemetry & Transparent Risk Scoring

ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS risk_score INT DEFAULT 15;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS risk_level VARCHAR(16) DEFAULT 'LOW';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS risk_factors VARCHAR(500);

ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS reconciled_confidence INT DEFAULT 95;
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS disruption_evidence TEXT;
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS weather_summary VARCHAR(500);
