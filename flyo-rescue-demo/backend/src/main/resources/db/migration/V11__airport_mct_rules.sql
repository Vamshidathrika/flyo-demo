-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V11
-- STAGE 13C: ZERO HARDCODED OPERATIONAL DATA - DATABASE-DRIVEN AIRPORT MCT RULES
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

CREATE TABLE IF NOT EXISTS airport_mct_rules (
    id VARCHAR(64) PRIMARY KEY,
    airport_code VARCHAR(8) NOT NULL,
    transfer_type VARCHAR(64) NOT NULL,
    published_mct_minutes INT NOT NULL,
    flyo_policy_minutes INT NOT NULL,
    source_authority VARCHAR(128) NOT NULL,
    rule_version VARCHAR(32) NOT NULL DEFAULT 'v2026.1',
    effective_date TIMESTAMP NOT NULL,
    expiry_date TIMESTAMP,
    approval_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    approved_by VARCHAR(64) NOT NULL DEFAULT 'OCC_SAFETY_COMMITTEE',
    last_verified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provenance_reference VARCHAR(256),
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mct_airport_type ON airport_mct_rules(airport_code, transfer_type);
CREATE INDEX IF NOT EXISTS idx_mct_tenant_airport ON airport_mct_rules(tenant_id, airport_code);
CREATE INDEX IF NOT EXISTS idx_mct_approval_status ON airport_mct_rules(approval_status);

-- Seed authoritative published MCT rules with complete provenance and regulatory metadata
-- 1. Delhi Indira Gandhi International (DEL)
INSERT INTO airport_mct_rules (id, airport_code, transfer_type, published_mct_minutes, flyo_policy_minutes, source_authority, rule_version, effective_date, approval_status, approved_by, last_verified_at, provenance_reference, tenant_id)
VALUES
('MCT-DEL-D-D', 'DEL', 'DOMESTIC_TO_DOMESTIC', 60, 75, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'AAI AIP India ENR 1.4 & IATA SSIM App D', 'tenant_default'),
('MCT-DEL-D-I', 'DEL', 'DOMESTIC_TO_INTERNATIONAL', 90, 120, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'AAI AIP India ENR 1.4 & IATA SSIM App D', 'tenant_default'),
('MCT-DEL-I-D', 'DEL', 'INTERNATIONAL_TO_DOMESTIC', 120, 120, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'AAI AIP India ENR 1.4 & IATA SSIM App D', 'tenant_default'),
('MCT-DEL-I-I', 'DEL', 'INTERNATIONAL_TO_INTERNATIONAL', 90, 120, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'AAI AIP India ENR 1.4 & IATA SSIM App D', 'tenant_default'),
('MCT-DEL-TERM', 'DEL', 'INTER_TERMINAL', 120, 150, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'DIAL T1-T3 Transit Guide', 'tenant_default');

-- 2. Mumbai Chhatrapati Shivaji Maharaj International (BOM)
INSERT INTO airport_mct_rules (id, airport_code, transfer_type, published_mct_minutes, flyo_policy_minutes, source_authority, rule_version, effective_date, approval_status, approved_by, last_verified_at, provenance_reference, tenant_id)
VALUES
('MCT-BOM-D-D', 'BOM', 'DOMESTIC_TO_DOMESTIC', 60, 75, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'CSMIA Operations & AAI AIP Section 2.2', 'tenant_default'),
('MCT-BOM-D-I', 'BOM', 'DOMESTIC_TO_INTERNATIONAL', 90, 120, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'CSMIA Operations & AAI AIP Section 2.2', 'tenant_default'),
('MCT-BOM-I-D', 'BOM', 'INTERNATIONAL_TO_DOMESTIC', 120, 120, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'CSMIA Operations & AAI AIP Section 2.2', 'tenant_default'),
('MCT-BOM-I-I', 'BOM', 'INTERNATIONAL_TO_INTERNATIONAL', 90, 120, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'CSMIA Operations & AAI AIP Section 2.2', 'tenant_default'),
('MCT-BOM-TERM', 'BOM', 'INTER_TERMINAL', 120, 150, 'Airports Authority of India (AAI) / DGCA AIP India', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'CSMIA Inter-Terminal Transfer Directive', 'tenant_default');

-- 3. Bengaluru Kempegowda International (BLR)
INSERT INTO airport_mct_rules (id, airport_code, transfer_type, published_mct_minutes, flyo_policy_minutes, source_authority, rule_version, effective_date, approval_status, approved_by, last_verified_at, provenance_reference, tenant_id)
VALUES
('MCT-BLR-D-D', 'BLR', 'DOMESTIC_TO_DOMESTIC', 45, 75, 'Bangalore International Airport Limited (BIAL) / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'BIAL Operational Manual & IATA SSIM 2026', 'tenant_default'),
('MCT-BLR-D-I', 'BLR', 'DOMESTIC_TO_INTERNATIONAL', 75, 120, 'Bangalore International Airport Limited (BIAL) / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'BIAL Operational Manual & IATA SSIM 2026', 'tenant_default'),
('MCT-BLR-I-D', 'BLR', 'INTERNATIONAL_TO_DOMESTIC', 90, 120, 'Bangalore International Airport Limited (BIAL) / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'BIAL Operational Manual & IATA SSIM 2026', 'tenant_default'),
('MCT-BLR-I-I', 'BLR', 'INTERNATIONAL_TO_INTERNATIONAL', 75, 120, 'Bangalore International Airport Limited (BIAL) / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'BIAL Operational Manual & IATA SSIM 2026', 'tenant_default'),
('MCT-BLR-TERM', 'BLR', 'INTER_TERMINAL', 90, 150, 'Bangalore International Airport Limited (BIAL) / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'BIAL T1-T2 Shuttle Transfer SOP', 'tenant_default');

-- 4. Hyderabad Rajiv Gandhi International (HYD)
INSERT INTO airport_mct_rules (id, airport_code, transfer_type, published_mct_minutes, flyo_policy_minutes, source_authority, rule_version, effective_date, approval_status, approved_by, last_verified_at, provenance_reference, tenant_id)
VALUES
('MCT-HYD-D-D', 'HYD', 'DOMESTIC_TO_DOMESTIC', 45, 75, 'GMR Hyderabad International Airport / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'GHIAL Aerodrome Ops Manual 2026', 'tenant_default'),
('MCT-HYD-D-I', 'HYD', 'DOMESTIC_TO_INTERNATIONAL', 75, 120, 'GMR Hyderabad International Airport / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'GHIAL Aerodrome Ops Manual 2026', 'tenant_default'),
('MCT-HYD-I-D', 'HYD', 'INTERNATIONAL_TO_DOMESTIC', 90, 120, 'GMR Hyderabad International Airport / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'GHIAL Aerodrome Ops Manual 2026', 'tenant_default'),
('MCT-HYD-I-I', 'HYD', 'INTERNATIONAL_TO_INTERNATIONAL', 75, 120, 'GMR Hyderabad International Airport / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'GHIAL Aerodrome Ops Manual 2026', 'tenant_default'),
('MCT-HYD-TERM', 'HYD', 'INTER_TERMINAL', 90, 150, 'GMR Hyderabad International Airport / AAI', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'GHIAL Integrated Terminal Operations', 'tenant_default');

-- 5. London Heathrow (LHR)
INSERT INTO airport_mct_rules (id, airport_code, transfer_type, published_mct_minutes, flyo_policy_minutes, source_authority, rule_version, effective_date, approval_status, approved_by, last_verified_at, provenance_reference, tenant_id)
VALUES
('MCT-LHR-D-D', 'LHR', 'DOMESTIC_TO_DOMESTIC', 60, 75, 'Civil Aviation Authority (CAA UK) / HAL', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Heathrow Airport Ltd Operations Handbook 2026', 'tenant_default'),
('MCT-LHR-D-I', 'LHR', 'DOMESTIC_TO_INTERNATIONAL', 75, 120, 'Civil Aviation Authority (CAA UK) / HAL', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Heathrow Airport Ltd Operations Handbook 2026', 'tenant_default'),
('MCT-LHR-I-D', 'LHR', 'INTERNATIONAL_TO_DOMESTIC', 90, 120, 'Civil Aviation Authority (CAA UK) / HAL', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Heathrow Airport Ltd Operations Handbook 2026', 'tenant_default'),
('MCT-LHR-I-I', 'LHR', 'INTERNATIONAL_TO_INTERNATIONAL', 90, 120, 'Civil Aviation Authority (CAA UK) / HAL', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Heathrow Airport Ltd Operations Handbook 2026', 'tenant_default'),
('MCT-LHR-TERM', 'LHR', 'INTER_TERMINAL', 105, 150, 'Civil Aviation Authority (CAA UK) / HAL', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'HAL Flight Connections Flight Connection Center', 'tenant_default');

-- 6. Frankfurt am Main (FRA)
INSERT INTO airport_mct_rules (id, airport_code, transfer_type, published_mct_minutes, flyo_policy_minutes, source_authority, rule_version, effective_date, approval_status, approved_by, last_verified_at, provenance_reference, tenant_id)
VALUES
('MCT-FRA-D-D', 'FRA', 'DOMESTIC_TO_DOMESTIC', 45, 75, 'Fraport AG / Luftfahrt-Bundesamt (LBA)', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Fraport Airport Operations AIP Germany 2026', 'tenant_default'),
('MCT-FRA-D-I', 'FRA', 'DOMESTIC_TO_INTERNATIONAL', 60, 120, 'Fraport AG / Luftfahrt-Bundesamt (LBA)', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Fraport Airport Operations AIP Germany 2026', 'tenant_default'),
('MCT-FRA-I-D', 'FRA', 'INTERNATIONAL_TO_DOMESTIC', 75, 120, 'Fraport AG / Luftfahrt-Bundesamt (LBA)', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Fraport Airport Operations AIP Germany 2026', 'tenant_default'),
('MCT-FRA-I-I', 'FRA', 'INTERNATIONAL_TO_INTERNATIONAL', 60, 120, 'Fraport AG / Luftfahrt-Bundesamt (LBA)', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Fraport Airport Operations AIP Germany 2026', 'tenant_default'),
('MCT-FRA-TERM', 'FRA', 'INTER_TERMINAL', 90, 150, 'Fraport AG / Luftfahrt-Bundesamt (LBA)', 'v2026.1', TIMESTAMP '2026-01-01 00:00:00', 'APPROVED', 'OCC_SAFETY_COMMITTEE', CURRENT_TIMESTAMP, 'Fraport Skyline Transit Service Guidelines', 'tenant_default');
