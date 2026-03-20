CREATE TABLE IF NOT EXISTS provents (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount_per_share DECIMAL(19, 8) NOT NULL CHECK (amount_per_share > 0),
    com_date TIMESTAMP WITH TIME ZONE NOT NULL,
    payment_date TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_provent_asset FOREIGN KEY (asset_id) REFERENCES assets(id),
    CONSTRAINT chk_provent_payment_after_com CHECK (payment_date >= com_date)
);

CREATE UNIQUE INDEX uk_provents_asset_type_dates
    ON provents (asset_id, type, com_date, payment_date);

CREATE TABLE IF NOT EXISTS provent_provisions (
    id UUID PRIMARY KEY,
    provent_id UUID NOT NULL,
    user_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    quantity_on_com_date DECIMAL(19, 8) NOT NULL CHECK (quantity_on_com_date >= 0),
    gross_amount DECIMAL(19, 8) NOT NULL CHECK (gross_amount >= 0),
    withholding_tax_amount DECIMAL(19, 8) NOT NULL CHECK (withholding_tax_amount >= 0),
    net_amount DECIMAL(19, 8) NOT NULL CHECK (net_amount >= 0),
    status VARCHAR(20) NOT NULL,
    CONSTRAINT fk_provision_provent FOREIGN KEY (provent_id) REFERENCES provents(id),
    CONSTRAINT fk_provision_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_provision_asset FOREIGN KEY (asset_id) REFERENCES assets(id),
    CONSTRAINT uk_provision_unique_user_provent UNIQUE (provent_id, user_id)
);

CREATE INDEX idx_provent_provisions_user ON provent_provisions (user_id);
CREATE INDEX idx_provent_provisions_payment ON provent_provisions (provent_id, status);
