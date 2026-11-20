ALTER TABLE assets
    ADD COLUMN rate_type VARCHAR(30),
    ADD COLUMN spread_rate DECIMAL(19, 6);

UPDATE assets
SET rate_type = 'FIXED'
WHERE category = 'RENDA_FIXA'
  AND rate_type IS NULL
  AND (indexer IS NULL OR indexer = 'PRE');
