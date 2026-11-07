-- Flyo Disruption Recovery Platform: Schema Migration V4
-- Compliance Target: PCI-DSS v4.0 Requirement 3.2 & Sensitive Authentication Data (SAD) Elimination
-- Database Engines: PostgreSQL 14+ / H2 (PostgreSQL mode)

-- 1. Add Tokenized Reference and Masked Display Columns
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS card_token_id VARCHAR(128);
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS last_four VARCHAR(4);
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS card_brand VARCHAR(32) DEFAULT 'Mastercard';

-- 2. Populate token and last_four for any existing records before column removal
UPDATE virtual_cards 
SET last_four = CASE 
    WHEN card_number IS NOT NULL AND LENGTH(card_number) >= 4 THEN SUBSTRING(card_number, LENGTH(card_number) - 3, 4)
    ELSE '0000'
END
WHERE last_four IS NULL;

UPDATE virtual_cards 
SET card_token_id = 'tok_legacy_' || id
WHERE card_token_id IS NULL;

-- 3. Enforce NOT NULL constraints on tokenized columns
ALTER TABLE virtual_cards ALTER COLUMN card_token_id SET NOT NULL;
ALTER TABLE virtual_cards ALTER COLUMN last_four SET NOT NULL;

-- 4. Strictly Drop Prohibited CVV (PCI-DSS Requirement 3.2 forbids retention after auth)
ALTER TABLE virtual_cards DROP COLUMN IF EXISTS cvv;

-- 5. Drop Raw Plaintext Card Number (PAN)
ALTER TABLE virtual_cards DROP COLUMN IF EXISTS card_number;

-- 6. Add Unique Index on Secure Token Reference
CREATE UNIQUE INDEX IF NOT EXISTS idx_virtual_cards_token ON virtual_cards(card_token_id);
