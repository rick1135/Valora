CREATE TABLE benchmarks (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    ticker VARCHAR(20) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE benchmark_values (
    id BIGSERIAL PRIMARY KEY,
    benchmark_id UUID NOT NULL REFERENCES benchmarks(id) ON DELETE CASCADE,
    reference_date DATE NOT NULL,
    value DECIMAL(19, 10) NOT NULL,
    UNIQUE (benchmark_id, reference_date)
);

CREATE INDEX idx_benchmark_values_date ON benchmark_values(reference_date);

-- Insert initial benchmarks
INSERT INTO benchmarks (id, name, ticker, type, provider) VALUES 
(gen_random_uuid(), 'Ibovespa', '^BVSP', 'INDEX', 'BRAPI'),
(gen_random_uuid(), 'CDI', 'CDI', 'INTEREST_RATE', 'BCB'),
(gen_random_uuid(), 'IPCA', 'IPCA', 'INFLATION', 'BCB');
