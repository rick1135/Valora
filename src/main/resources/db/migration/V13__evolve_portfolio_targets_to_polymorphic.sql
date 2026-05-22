ALTER TABLE portfolio_targets
    ALTER COLUMN asset_category DROP NOT NULL;

ALTER TABLE portfolio_targets
    ADD COLUMN asset_id UUID NULL;

ALTER TABLE portfolio_targets
    ADD CONSTRAINT fk_portfolio_targets_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id);

ALTER TABLE portfolio_targets
    ADD CONSTRAINT chk_target_exclusivity
        CHECK (
            (asset_category IS NOT NULL AND asset_id IS NULL)
            OR (asset_category IS NULL AND asset_id IS NOT NULL)
        );

CREATE UNIQUE INDEX ux_portfolio_targets_portfolio_asset
    ON portfolio_targets(portfolio_id, asset_id)
    WHERE asset_id IS NOT NULL;
