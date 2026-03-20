ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_source VARCHAR(20);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_event_key VARCHAR(1024);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_label VARCHAR(100);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_related_to VARCHAR(100);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_asset_issued VARCHAR(50);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_isin_code VARCHAR(20);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_remarks VARCHAR(255);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_approved_on TIMESTAMP WITH TIME ZONE;
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_last_date_prior TIMESTAMP WITH TIME ZONE;
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_rate DECIMAL(19, 8);
ALTER TABLE provents ADD COLUMN IF NOT EXISTS origin_rate_basis VARCHAR(20);

UPDATE provents
SET origin_source = COALESCE(origin_source, 'MANUAL'),
    origin_event_key = COALESCE(
        origin_event_key,
        CONCAT(
            'MANUAL|',
            asset_id::text, '|',
            type, '|',
            com_date::text, '|',
            payment_date::text, '|',
            amount_per_share::text, '|',
            'MANUAL'
        )
    ),
    origin_rate = COALESCE(origin_rate, amount_per_share),
    origin_rate_basis = COALESCE(origin_rate_basis, 'NET');

ALTER TABLE provents ALTER COLUMN origin_source SET NOT NULL;
ALTER TABLE provents ALTER COLUMN origin_event_key SET NOT NULL;
ALTER TABLE provents ALTER COLUMN origin_rate SET NOT NULL;
ALTER TABLE provents ALTER COLUMN origin_rate_basis SET NOT NULL;

DROP INDEX IF EXISTS uk_provents_asset_type_dates;

CREATE UNIQUE INDEX IF NOT EXISTS uk_provents_origin_source_event
    ON provents (origin_source, origin_event_key);
