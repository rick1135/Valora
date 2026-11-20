ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE assets
    ADD COLUMN owner_user_id UUID REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE assets
    DROP CONSTRAINT IF EXISTS assets_ticker_normalized_key;

CREATE UNIQUE INDEX ux_assets_global_ticker_normalized
    ON assets (ticker_normalized)
    WHERE owner_user_id IS NULL;

CREATE UNIQUE INDEX ux_assets_user_ticker_normalized
    ON assets (owner_user_id, ticker_normalized)
    WHERE owner_user_id IS NOT NULL;

CREATE INDEX idx_assets_owner_user_id ON assets (owner_user_id);
