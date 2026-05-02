CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS portfolios (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_portfolio_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_portfolios_user_name_normalized
    ON portfolios (user_id, lower(name));

CREATE INDEX IF NOT EXISTS idx_portfolios_user
    ON portfolios (user_id);

INSERT INTO portfolios (id, user_id, name, created_at, updated_at)
SELECT gen_random_uuid(),
       u.id,
       'Principal',
       COALESCE(u.created_at, CURRENT_TIMESTAMP),
       COALESCE(u.updated_at, CURRENT_TIMESTAMP)
FROM users u
WHERE NOT EXISTS (
    SELECT 1
    FROM portfolios p
    WHERE p.user_id = u.id
      AND lower(p.name) = lower('Principal')
);

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS portfolio_id UUID;

UPDATE transactions t
SET portfolio_id = p.id
FROM portfolios p
WHERE p.user_id = t.user_id
  AND lower(p.name) = lower('Principal')
  AND t.portfolio_id IS NULL;

ALTER TABLE transactions ALTER COLUMN portfolio_id SET NOT NULL;
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS fk_transaction_user;
ALTER TABLE transactions ADD CONSTRAINT fk_transaction_portfolio
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id);
DROP INDEX IF EXISTS idx_transactions_user_asset_date;
DROP INDEX IF EXISTS idx_transactions_user_date;
CREATE INDEX IF NOT EXISTS idx_transactions_portfolio_asset_date
    ON transactions (portfolio_id, asset_id, transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_portfolio_date
    ON transactions (portfolio_id, transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_asset_date_portfolio
    ON transactions (asset_id, transaction_date DESC, portfolio_id);
ALTER TABLE transactions DROP COLUMN IF EXISTS user_id;

ALTER TABLE positions ADD COLUMN IF NOT EXISTS portfolio_id UUID;

UPDATE positions pos
SET portfolio_id = p.id
FROM portfolios p
WHERE p.user_id = pos.user_id
  AND lower(p.name) = lower('Principal')
  AND pos.portfolio_id IS NULL;

ALTER TABLE positions ALTER COLUMN portfolio_id SET NOT NULL;
ALTER TABLE positions DROP CONSTRAINT IF EXISTS fk_position_user;
ALTER TABLE positions DROP CONSTRAINT IF EXISTS uk_position_user_asset;
ALTER TABLE positions ADD CONSTRAINT fk_position_portfolio
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id);
ALTER TABLE positions ADD CONSTRAINT uk_position_portfolio_asset
    UNIQUE (portfolio_id, asset_id);
DROP INDEX IF EXISTS idx_positions_user;
CREATE INDEX IF NOT EXISTS idx_positions_portfolio
    ON positions (portfolio_id);
ALTER TABLE positions DROP COLUMN IF EXISTS user_id;

ALTER TABLE provent_provisions ADD COLUMN IF NOT EXISTS portfolio_id UUID;

UPDATE provent_provisions pp
SET portfolio_id = p.id
FROM portfolios p
WHERE p.user_id = pp.user_id
  AND lower(p.name) = lower('Principal')
  AND pp.portfolio_id IS NULL;

ALTER TABLE provent_provisions ALTER COLUMN portfolio_id SET NOT NULL;
ALTER TABLE provent_provisions DROP CONSTRAINT IF EXISTS fk_provision_user;
ALTER TABLE provent_provisions DROP CONSTRAINT IF EXISTS uk_provision_unique_user_provent;
ALTER TABLE provent_provisions ADD CONSTRAINT fk_provision_portfolio
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id);
ALTER TABLE provent_provisions ADD CONSTRAINT uk_provision_unique_portfolio_provent
    UNIQUE (provent_id, portfolio_id);
DROP INDEX IF EXISTS idx_provent_provisions_user;
CREATE INDEX IF NOT EXISTS idx_provent_provisions_portfolio
    ON provent_provisions (portfolio_id);
ALTER TABLE provent_provisions DROP COLUMN IF EXISTS user_id;
