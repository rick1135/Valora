ALTER TABLE transactions
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN idempotency_payload TEXT;

CREATE UNIQUE INDEX ux_transactions_portfolio_idempotency_key
    ON transactions (portfolio_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
