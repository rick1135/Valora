CREATE TABLE portfolio_targets (
    id UUID PRIMARY KEY,
    portfolio_id UUID NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    asset_category VARCHAR(20) NOT NULL,
    target_percentage DECIMAL(5, 2) NOT NULL,
    UNIQUE (portfolio_id, asset_category)
);

CREATE INDEX idx_portfolio_targets_portfolio ON portfolio_targets(portfolio_id);
