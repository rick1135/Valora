CREATE TABLE IF NOT EXISTS transactions (
                                            id UUID PRIMARY KEY,
                                            user_id UUID NOT NULL,
                                            asset_id UUID NOT NULL,
                                            type VARCHAR(20) NOT NULL,
                                            quantity DECIMAL(19, 8) NOT NULL CHECK (quantity > 0),
                                            unit_price DECIMAL(19, 8) NOT NULL CHECK (unit_price > 0),
                                            transaction_date TIMESTAMP WITH TIME ZONE NOT NULL,
                                            CONSTRAINT fk_transaction_user FOREIGN KEY (user_id) REFERENCES users(id),
                                            CONSTRAINT fk_transaction_asset FOREIGN KEY (asset_id) REFERENCES assets(id)
);

CREATE TABLE IF NOT EXISTS positions (
                                         id UUID PRIMARY KEY,
                                         user_id UUID NOT NULL,
                                         asset_id UUID NOT NULL,
                                         quantity DECIMAL(19, 8) NOT NULL CHECK (quantity >= 0),
                                         average_price DECIMAL(19, 8) NOT NULL CHECK (average_price >= 0),
                                         version BIGINT NOT NULL DEFAULT 0,
                                         CONSTRAINT fk_position_user FOREIGN KEY (user_id) REFERENCES users(id),
                                         CONSTRAINT fk_position_asset FOREIGN KEY (asset_id) REFERENCES assets(id),
                                         CONSTRAINT uk_position_user_asset UNIQUE (user_id, asset_id)
);

-- Acelera a busca do extrato de um usuário para um ativo específico, ordenado por data
CREATE INDEX idx_transactions_user_asset_date ON transactions (user_id, asset_id, transaction_date DESC);

-- Acelera a busca de todas as transações de um usuário (ex: dashboard geral)
CREATE INDEX idx_transactions_user_date ON transactions (user_id, transaction_date DESC);

-- Acelera o carregamento da carteira atual do usuário
CREATE INDEX idx_positions_user ON positions (user_id);
