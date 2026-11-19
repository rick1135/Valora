-- Seed inicial de catalogo representativo de ativos para o MVP.
-- Nao sobrescreve tickers ja cadastrados na base.

INSERT INTO assets (
    id,
    ticker,
    ticker_normalized,
    name,
    category,
    indexer,
    annual_rate,
    issuer,
    expiration_date
) VALUES
    -- Acoes (inclui PETR4 para smoke e onboarding)
    (gen_random_uuid(), 'PETR4', 'PETR4', 'Petrobras PN', 'ACOES', NULL, NULL, NULL, NULL),
    (gen_random_uuid(), 'VALE3', 'VALE3', 'Vale ON', 'ACOES', NULL, NULL, NULL, NULL),
    (gen_random_uuid(), 'ITUB4', 'ITUB4', 'Itau Unibanco PN', 'ACOES', NULL, NULL, NULL, NULL),
    (gen_random_uuid(), 'WEGE3', 'WEGE3', 'WEG ON', 'ACOES', NULL, NULL, NULL, NULL),

    -- Fundos Imobiliarios (FII)
    (gen_random_uuid(), 'HGLG11', 'HGLG11', 'CSHG Logistica FII', 'FII', NULL, NULL, NULL, NULL),
    (gen_random_uuid(), 'MXRF11', 'MXRF11', 'Maxi Renda FII', 'FII', NULL, NULL, NULL, NULL),

    -- ETFs
    (gen_random_uuid(), 'BOVA11', 'BOVA11', 'iShares Ibovespa Fundo de Indice', 'ETF', NULL, NULL, NULL, NULL),
    (gen_random_uuid(), 'IVVB11', 'IVVB11', 'iShares S&P 500 FIC FI IE', 'ETF', NULL, NULL, NULL, NULL),

    -- Criptoativos
    (gen_random_uuid(), 'BTC', 'BTC', 'Bitcoin', 'CRIPTO', NULL, NULL, NULL, NULL),
    (gen_random_uuid(), 'ETH', 'ETH', 'Ethereum', 'CRIPTO', NULL, NULL, NULL, NULL)
ON CONFLICT (ticker_normalized) DO NOTHING;
