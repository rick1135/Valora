CREATE TABLE portfolio_daily_snapshots (
    id BIGSERIAL PRIMARY KEY,
    portfolio_id UUID NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    snapshot_date DATE NOT NULL,
    total_patrimony DECIMAL(19, 8) NOT NULL,
    total_invested DECIMAL(19, 8) NOT NULL,
    net_cash_flow DECIMAL(19, 8) NOT NULL,
    provents_received DECIMAL(19, 8) NOT NULL,
    daily_return_factor DECIMAL(19, 12) NOT NULL CHECK (daily_return_factor >= 0),
    twr_quota_value DECIMAL(19, 12) NOT NULL CHECK (twr_quota_value >= 0),
    pricing_complete BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (portfolio_id, snapshot_date)
);

CREATE INDEX idx_portfolio_daily_snapshots_portfolio_date
    ON portfolio_daily_snapshots (portfolio_id, snapshot_date DESC);
